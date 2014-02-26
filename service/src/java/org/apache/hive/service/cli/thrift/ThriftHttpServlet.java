/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hive.service.cli.thrift;

import java.io.IOException;
import java.security.PrivilegedExceptionAction;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hive.service.auth.HiveAuthFactory;
import org.apache.hive.service.auth.HttpAuthUtils;
import org.apache.hive.service.auth.HttpAuthenticationException;
import org.apache.hive.service.cli.session.SessionManager;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.server.TServlet;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.Oid;

/**
 * 
 * ThriftHttpServlet
 *
 */
public class ThriftHttpServlet extends TServlet {

  private static final long serialVersionUID = 1L;
  public static final Log LOG = LogFactory.getLog(ThriftHttpServlet.class.getName());
  private final String authType;
  private final UserGroupInformation serviceUGI;

  public ThriftHttpServlet(TProcessor processor, TProtocolFactory protocolFactory,
      String authType, UserGroupInformation serviceUGI) {
    super(processor, protocolFactory);
    this.authType = authType;
    this.serviceUGI = serviceUGI;
  }

  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {
    try {
      // Each request needs to have an auth header;
      // either Basic or Negotiate
      verifyAuthHeader(request);

      // Set the thread local username to be used for doAs if true
      SessionManager.setUserName(getUsername(request, authType));

      // For a kerberos setup
      if(isKerberosAuthMode(authType)) {
        doKerberosAuth(request);
      }

      logRequestHeader(request, authType);
      super.doPost(request, response);

      // Clear the thread local username since we set it in each http request
      SessionManager.clearUserName();
    }
    catch (HttpAuthenticationException e) {
      // Send a 403 to the client
      LOG.error("Error: ", e);
      response.setContentType("application/x-thrift");
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      // Send the response back to the client
      response.getWriter().println("Authentication Error: " + e.getMessage());
    }
  }

  /**
   * Each request should have an Authorization header field.
   * @param request
   * @return
   * @throws HttpAuthenticationException
   */
  private void verifyAuthHeader(HttpServletRequest request)
      throws HttpAuthenticationException {
    String authHeader = request.getHeader(HttpAuthUtils.AUTHORIZATION);
    if (authHeader == null) {
      throw new HttpAuthenticationException("Request contains no Authorization header.");
    }
  }

  /**
   * Do the GSS-API kerberos authentication.
   * We already have a logged in subject in the form of serviceUGI,
   * which GSS-API will extract information from.
   * @param request
   * @return
   * @throws HttpAuthenticationException
   */
  private Void doKerberosAuth(HttpServletRequest request)
      throws HttpAuthenticationException {
    try {
      return serviceUGI.doAs(new HttpKerberosServerAction(request));
    } catch (Exception e) {
      throw new HttpAuthenticationException(e);
    }
  }

  class HttpKerberosServerAction implements PrivilegedExceptionAction<Void> {
    HttpServletRequest request;

    HttpKerberosServerAction(HttpServletRequest request) {
      this.request = request;
    }

    @Override
    public Void run() throws HttpAuthenticationException {
      // Get own Kerberos credentials for accepting connection
      GSSManager manager = GSSManager.getInstance();
      GSSContext gssContext = null;
      try {
        // This Oid for Kerberos GSS-API mechanism.
        Oid krb5Oid = new Oid("1.2.840.113554.1.2.2");
        GSSCredential serverCreds = manager.createCredential(null,
            GSSCredential.DEFAULT_LIFETIME,  krb5Oid, GSSCredential.ACCEPT_ONLY);

        // Create a GSS context
        gssContext = manager.createContext(serverCreds);

        // Get service ticket from the authorization header
        String serviceTicket = getPassToken(request,
            HiveAuthFactory.AuthTypes.KERBEROS.toString());
        byte[] inToken = serviceTicket.getBytes();
        gssContext.acceptSecContext(inToken, 0, inToken.length);

        // Authenticate or deny based on its context completion
        if (!gssContext.isEstablished()) {
          throw new HttpAuthenticationException("Kerberos authentication failed: " +
              "unable to establish context with the service ticket " +
              "provided by the client.");
        }
      }
      catch (GSSException e) {
        throw new HttpAuthenticationException("Kerberos authentication failed: ", e);
      }
      finally {
        if (gssContext != null) {
          try {
            gssContext.dispose();
          } catch (GSSException e) {
            // No-op
          }
        }
      }
      return null;
    }
  }

  private String getUsername(HttpServletRequest request, String authType)
      throws HttpAuthenticationException {
    String[] creds = getAuthHeaderFields(request, authType);
    // Username must be present
    if (creds[0] == null || creds[0].isEmpty()) {
      throw new HttpAuthenticationException("Authorization header received " +
          "from the client does not contain username.");
    }
    return creds[0];
  }

  private String getPassToken(HttpServletRequest request, String authType)
      throws HttpAuthenticationException {
    String[] creds = getAuthHeaderFields(request, authType);
    // Service ticket / password must be present
    if (creds[1] == null || creds[1].isEmpty()) {
      if (isKerberosAuthMode(authType)) {
        throw new HttpAuthenticationException("Authorization header received " +
            "from the client does not contain the service ticket.");
      }
      else {
        throw new HttpAuthenticationException("Authorization header received " +
            "from the client does not contain the password.");
      }
    }
    return creds[1];
  }

  /**
   * Decodes the base 64 encoded authorization header payload,
   * to return an array of string containing the header fields.
   * The header fields are created by encoding - "<username>:<passToken>",
   * in base 64 format on the client side. In case of kerberos, the passToken,
   * is the service ticket provided by the client.
   * @param request
   * @param authType
   * @return
   * @throws HttpAuthenticationException
   */
  private String[] getAuthHeaderFields(HttpServletRequest request,
      String authType) throws HttpAuthenticationException {
    String authHeader = request.getHeader(HttpAuthUtils.AUTHORIZATION);
    // Each http request must have an Authorization header
    if (authHeader == null || authHeader.isEmpty()) {
      throw new HttpAuthenticationException("Authorization header received " +
          "from the client is empty.");
    }

    String authHeaderBase64;
    if (isKerberosAuthMode(authType)) {
      authHeaderBase64 = authHeader.substring((HttpAuthUtils.NEGOTIATE + " ").length());
    }
    else {
      authHeaderBase64 = authHeader.substring((HttpAuthUtils.BASIC + " ").length());
    }
    // Authorization header must have a payload
    if (authHeaderBase64 == null || authHeaderBase64.isEmpty()) {
      throw new HttpAuthenticationException("Authorization header received " +
          "from the client does not contain any data.");
    }

    String authHeaderString = StringUtils.newStringUtf8(
        Base64.decodeBase64(authHeaderBase64.getBytes()));
    String[] creds = authHeaderString.split(":");
    return creds;
  }

  private boolean isKerberosAuthMode(String authType) {
    return authType.equalsIgnoreCase(HiveAuthFactory.AuthTypes.KERBEROS.toString());
  }

  protected void logRequestHeader(HttpServletRequest request, String authType) {
    String username;
    try {
      username = getUsername(request, authType);
      LOG.debug("HttpServlet:  HTTP Authorization header -  username=" + username +
          " auth mode=" + authType);
    } catch (HttpAuthenticationException e) {
      LOG.debug(e);
    }
  }
}


