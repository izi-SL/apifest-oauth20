/*
 * Copyright 2013-2014, ApiFest project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apifest.oauth20;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Date;

import org.apache.commons.codec.binary.Base64;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.QueryStringEncoder;
import org.jboss.netty.util.CharsetUtil;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main class for authorization.
 *
 * @author Rossitsa Borissova
 */
public class AuthorizationServer {

    static final String BASIC = "Basic ";
    private static final String TOKEN_TYPE_BEARER = "Bearer";
    protected static final String SCOPE_NOK_MESSAGE = "{\"status\":\"scope not valid\"}";

    protected static Logger log = LoggerFactory.getLogger(AuthorizationServer.class);

    protected DBManager db = DBManagerFactory.getInstance();
    protected ScopeService scopeService = new ScopeService();

    public ClientCredentials issueClientCredentials(HttpRequest req) throws OAuthException {
        ClientCredentials creds = null;
        String content = req.getContent().toString(CharsetUtil.UTF_8);
        String contentType = req.headers().get(HttpHeaders.Names.CONTENT_TYPE);

        if (contentType != null && contentType.contains(Response.APPLICATION_JSON)) {
            ObjectMapper mapper = new ObjectMapper();
            ApplicationInfo appInfo;
            try {
                appInfo = mapper.readValue(content, ApplicationInfo.class);
                if (appInfo.valid()) {
                  String[] scopeList = appInfo.getScope().split(" ");
                  for (String s : scopeList) {
                      // TODO: add cache for scope
                      if (db.findScope(s) == null) {
                          throw new OAuthException(Response.SCOPE_NOT_EXIST,
                                  HttpResponseStatus.BAD_REQUEST);
                      }
                  }
                  creds = new ClientCredentials(appInfo.getName(), appInfo.getScope(), appInfo.getDescription(),
                          appInfo.getRedirectUri());
                  db.storeClientCredentials(creds);
                } else {
                    throw new OAuthException(Response.NAME_OR_SCOPE_OR_URI_IS_NULL, HttpResponseStatus.BAD_REQUEST);
                }
            } catch (JsonParseException e) {
                throw new OAuthException(Response.CANNOT_REGISTER_APP, HttpResponseStatus.BAD_REQUEST);
            } catch (JsonMappingException e) {
                throw new OAuthException(Response.CANNOT_REGISTER_APP, HttpResponseStatus.BAD_REQUEST);
            } catch (IOException e) {
                throw new OAuthException(Response.CANNOT_REGISTER_APP, HttpResponseStatus.BAD_REQUEST);
            }
        } else {
            throw new OAuthException(Response.UNSUPPORTED_MEDIA_TYPE, HttpResponseStatus.BAD_REQUEST);
        }
        return creds;
    }

    // /authorize?response_type=code&client_id=s6BhdRkqt3&state=xyz&redirect_uri=https%3A%2F%2Fclient%2Eexample%2Ecom%2Fcb
    public String issueAuthorizationCode(HttpRequest req) throws OAuthException {
        AuthRequest authRequest = new AuthRequest(req);
        log.debug("received client_id:" + authRequest.getClientId());
        if (!isValidClientId(authRequest.getClientId())) {
            throw new OAuthException(Response.INVALID_CLIENT_ID, HttpResponseStatus.BAD_REQUEST);
        }
        authRequest.validate();

        String scope = scopeService
                .getValidScope(authRequest.getScope(), authRequest.getClientId());
        if (scope == null) {
            throw new OAuthException(SCOPE_NOK_MESSAGE, HttpResponseStatus.BAD_REQUEST);
        }

        AuthCode authCode = new AuthCode(generateCode(), authRequest.getClientId(),
                authRequest.getRedirectUri(), authRequest.getState(), scope,
                authRequest.getResponseType(), authRequest.getUserId());
        log.debug("authCode: {}", authCode.getCode());
        db.storeAuthCode(authCode);

        // return redirect URI, append param code=[Authcode]
        QueryStringEncoder enc = new QueryStringEncoder(authRequest.getRedirectUri());
        enc.addParam("code", authCode.getCode());
        return enc.toString();
    }

    public AccessToken issueAccessToken(HttpRequest req) throws OAuthException {
        String clientId = getBasicAuthorizationClientId(req);
        if (clientId == null) {
            throw new OAuthException(Response.INVALID_CLIENT_ID, HttpResponseStatus.BAD_REQUEST);
        }
        TokenRequest tokenRequest = new TokenRequest(req);
        tokenRequest.setClientId(clientId);

        /*if(!isValidClientId(tokenRequest.getClientId())){
            throw new OAuthException(ErrorResponse.INVALID_CLIENT_ID, HttpResponseStatus.BAD_REQUEST);
        }*/
        tokenRequest.validate();

        AccessToken accessToken = null;
        if (TokenRequest.AUTHORIZATION_CODE.equals(tokenRequest.getGrantType())) {
            AuthCode authCode = findAuthCode(tokenRequest);
            // TODO: REVISIT: Move client_id check to db query
            if (authCode != null) {
                if (!tokenRequest.getClientId().equals(authCode.getClientId())) {
                    throw new OAuthException(Response.INVALID_CLIENT_ID,
                            HttpResponseStatus.BAD_REQUEST);
                }
                if (authCode.getRedirectUri() != null
                        && !tokenRequest.getRedirectUri().equals(authCode.getRedirectUri())) {
                    throw new OAuthException(Response.INVALID_REDIRECT_URI,
                            HttpResponseStatus.BAD_REQUEST);
                } else {
                    // invalidate the auth code
                    db.updateAuthCodeValidStatus(authCode.getCode(), false);
                    accessToken = new AccessToken(TOKEN_TYPE_BEARER, getExpiresIn(TokenRequest.PASSWORD,
                            authCode.getScope()), authCode.getScope());
                    accessToken.setUserId(authCode.getUserId());
                    accessToken.setClientId(authCode.getClientId());
                    accessToken.setCodeId(authCode.getId());
                    db.storeAccessToken(accessToken);
                }
            } else {
                throw new OAuthException(Response.INVALID_AUTH_CODE, HttpResponseStatus.BAD_REQUEST);
            }
        } else if (TokenRequest.REFRESH_TOKEN.equals(tokenRequest.getGrantType())) {
            accessToken = db.findAccessTokenByRefreshToken(tokenRequest.getRefreshToken(), tokenRequest.getClientId());
            if (accessToken != null) {
                String validScope = null;
                if (tokenRequest.getScope() != null) {
                    if (scopeService.scopeAllowed(tokenRequest.getScope(), accessToken.getScope())) {
                        validScope = tokenRequest.getScope();
                    } else {
                        throw new OAuthException(SCOPE_NOK_MESSAGE, HttpResponseStatus.BAD_REQUEST);
                    }
                } else {
                    validScope = accessToken.getScope();
                }
                db.updateAccessTokenValidStatus(accessToken.getToken(), false);
                AccessToken newAccessToken = new AccessToken(TOKEN_TYPE_BEARER,
                        getExpiresIn(TokenRequest.PASSWORD, validScope), validScope);
                newAccessToken.setUserId(accessToken.getUserId());
                newAccessToken.setDetails(accessToken.getDetails());
                newAccessToken.setClientId(accessToken.getClientId());
                db.storeAccessToken(newAccessToken);
                return newAccessToken;
            } else {
                throw new OAuthException(Response.INVALID_REFRESH_TOKEN,
                        HttpResponseStatus.BAD_REQUEST);
            }
        } else if (TokenRequest.CLIENT_CREDENTIALS.equals(tokenRequest.getGrantType())) {
            String scope = scopeService.getValidScope(tokenRequest.getScope(),
                    tokenRequest.getClientId());
            if (scope == null) {
                throw new OAuthException(SCOPE_NOK_MESSAGE, HttpResponseStatus.BAD_REQUEST);
            }

            accessToken = new AccessToken(TOKEN_TYPE_BEARER,
                    getExpiresIn(TokenRequest.CLIENT_CREDENTIALS, scope), scope, false);
            accessToken.setClientId(tokenRequest.getClientId());
            db.storeAccessToken(accessToken);
        } else if (TokenRequest.PASSWORD.equals(tokenRequest.getGrantType())) {
            String scope = scopeService.getValidScope(tokenRequest.getScope(),
                    tokenRequest.getClientId());
            if (scope == null) {
                throw new OAuthException(SCOPE_NOK_MESSAGE, HttpResponseStatus.BAD_REQUEST);
            }

            accessToken = new AccessToken(TOKEN_TYPE_BEARER, getExpiresIn(TokenRequest.PASSWORD, scope),
                    scope);
            try {
                UserDetails userDetails = authenticateUser(tokenRequest.getUsername(),
                        tokenRequest.getPassword());
                if (userDetails != null && userDetails.getUserId() != null) {
                    accessToken.setUserId(userDetails.getUserId());
                    accessToken.setDetails(userDetails.getDetails());
                    accessToken.setClientId(tokenRequest.getClientId());
                    db.storeAccessToken(accessToken);
                } else {
                    throw new OAuthException(Response.INVALID_USERNAME_PASSWORD,
                            HttpResponseStatus.UNAUTHORIZED);
                }
            } catch (IOException e) {
                log.error("Cannot authenticate user", e);
                throw new OAuthException(Response.CANNOT_AUTHENTICATE_USER,
                        HttpResponseStatus.UNAUTHORIZED); // NOSONAR
            }
        }
        return accessToken;
    }

    protected UserDetails authenticateUser(String username, String password) throws IOException {
        UserAuthentication ua = new UserAuthentication();
        return ua.authenticate(username, password);
    }

    protected String getBasicAuthorizationClientId(HttpRequest req) {
        // extract Basic Authorization header
        String authHeader = req.headers().get(HttpHeaders.Names.AUTHORIZATION);
        String clientId = null;
        if (authHeader != null && authHeader.contains(BASIC)) {
            String value = authHeader.replace(BASIC, "");
            Base64 decoder = new Base64();
            byte[] decodedBytes = decoder.decode(value);
            String decoded = new String(decodedBytes, Charset.forName("UTF-8"));
            // client_id:client_secret - should be changed by client password
            String[] str = decoded.split(":");
            if (str.length == 2) {
                String authClientId = str[0];
                String authClientSecret = str[1];
                // check valid - DB call
                if (db.validClient(authClientId, authClientSecret)) {
                    clientId = authClientId;
                }
            }
        }
        return clientId;
    }

    protected AuthCode findAuthCode(TokenRequest tokenRequest) {
        return db.findAuthCode(tokenRequest.getCode(), tokenRequest.getRedirectUri());
    }

    public AccessToken isValidToken(String token) {
        AccessToken accessToken = db.findAccessToken(token);
        if (accessToken != null) {
            if (accessToken.tokenExpired()) {
                db.updateAccessTokenValidStatus(accessToken.getToken(), false);
                return null;
            }
            return accessToken;
        }
        return null;
    }

    public ApplicationInfo getApplicationInfo(String clientId) {
        ApplicationInfo appInfo = null;
        // TODO: check clientid param mandatory
        ClientCredentials creds = db.findClientCredentials(clientId);
        if (creds != null) {
            appInfo = new ApplicationInfo();
            appInfo.setName(creds.getName());
            appInfo.setDescription(creds.getDescr());
            appInfo.setScope(creds.getScope());
            appInfo.setRedirectUri(creds.getUri());
            appInfo.setRegistered(new Date(creds.getCreated()));
        }
        return appInfo;
    }

    protected String generateCode() {
        return AuthCode.generate();
    }

    protected boolean isValidClientId(String clientId) {
        if (db.findClientCredentials(clientId) != null) {
            return true;
        }
        return false;
    }

    protected String getExpiresIn(String tokenGrantType, String scope) {
        return String.valueOf(scopeService.getExpiresIn(tokenGrantType, scope));
    }

    public boolean revokeToken(HttpRequest req) throws OAuthException {
        String clientId = getBasicAuthorizationClientId(req);
        if (clientId == null) {
            throw new OAuthException(Response.INVALID_CLIENT_ID, HttpResponseStatus.BAD_REQUEST);
        }

        String token = getAccessToken(req);
        AccessToken accessToken = db.findAccessToken(token);
        if (accessToken != null) {
            if (accessToken.tokenExpired()) {
                log.debug("access token {} is expired", token);
                return true;
            }
            if (clientId.equals(accessToken.getClientId())) {
                db.updateAccessTokenValidStatus(accessToken.getToken(), false);
                log.debug("access token {} set status invalid", token);
                return true;
            } else {
                log.debug("access token {} is not obtained for that clientId {}", token, clientId);
                return false;
            }
        }
        log.debug("access token {} not found", token);
        return false;
    }

    private String getAccessToken(HttpRequest req) {
        String content = req.getContent().toString(CharsetUtil.UTF_8);
        String token = null;
        try {
            JSONObject obj = new JSONObject(content);
            token = obj.getString("access_token");
        } catch (JSONException e) {
            log.error("cannot parse JSON, {}", content);
        }
        return token;
    }
}
