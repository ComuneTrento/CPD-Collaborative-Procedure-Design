package it.beng.modeler.microservice.subroute.auth;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.oauth2.AccessToken;
import io.vertx.ext.auth.oauth2.impl.AccessTokenImpl;
import io.vertx.ext.auth.oauth2.impl.OAuth2AuthProviderImpl;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.Session;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.codec.BodyCodec;
import it.beng.microservice.common.ServerError;
import it.beng.modeler.config;
import it.beng.modeler.microservice.http.JsonResponse;
import it.beng.modeler.microservice.subroute.AuthSubRoute;

import java.util.Map;
import java.util.logging.Logger;

/**
 * <p>This class is a member of <strong>modeler-microservice</strong> project.</p>
 *
 * @author vince
 */
public final class OAuth2ImplicitSubRoute extends OAuth2SubRoute {

    private static Logger logger = Logger.getLogger(OAuth2ImplicitSubRoute.class.getName());

    public static final String FLOW_TYPE = "IMPLICIT";

    public OAuth2ImplicitSubRoute(Vertx vertx, Router router, config.OAuth2Config oAuth2Config) {
        super(vertx, router, oAuth2Config, FLOW_TYPE);
    }

    /*
        https://simpatico.business-engineering.it/cpd/oauth2/server/callback,
        https://localhost:8901/cpd/oauth2/client/callback,
        https://simpatico.business-engineering.it/cpd/oauth2/client/callback,
        https://localhost:8901/cpd/oauth2/server/callback
    
    */

    @Override
    protected void init() {
        router.route(HttpMethod.GET, path + "login/handler").handler(this::loginHandler);
        router.route(HttpMethod.GET, baseHref + "oauth2/client/callback").handler(context -> {
            // TODO: check why reroute keeps the hash segment in safari
            context.reroute(config.server.appPath(context) + "oauth2/client/callback");
            /* this doesn't work in safari as it cleans the hash fragment on redirects... */
            // redirect(context, config.server.appPath(context) + "oauth2/client/callback");
        });
        router.route(HttpMethod.POST, path + "hash").handler(this::loginWithHash);
    }

    private void loginHandler(RoutingContext context) {
        StringBuilder url = new StringBuilder(oauth2ClientOptions.getSite() + oauth2ClientOptions
            .getAuthorizationPath())
            .append("?")
            .append("response_type=token")
            .append("&")
            .append("grant_type=implicit")
            .append("&")
            .append("redirect_uri=")
            .append(config.server.origin())
            .append(baseHref).append("oauth2/client/callback")
            .append("&")
            .append("client_id=")
            .append(oauth2ClientOptions.getClientID())
            .append("&")
            .append("scope=")
            .append(oauth2Flow.scopeString(" "))
            .append("&")
            .append("state=")
            .append((String) context.session().get("encodedState"));
        redirect(context, url.toString());
    }

    private void loginWithHash(final RoutingContext context) {
        final JsonObject hash = context.getBodyAsJson();
        logger.finest("receiverd hash: " + hash.encodePrettily());
        AuthSubRoute.checkEncodedStateStateCookie(context, hash.getString("state"));
        hash.remove("state");
        final AccessToken user = new AccessTokenImpl((OAuth2AuthProviderImpl) oauth2Provider, hash);
        // add user to the session
        context.setUser(user);
        Session session = context.session();
        if (session != null) {
            // the user has upgraded from unauthenticated to authenticated
            // session should be upgraded as recommended by owasp
            session.regenerateId();
        }
        final WebClient client = WebClient.create(vertx,
            new WebClientOptions().setUserAgent("CPD-WebClient/1.0").setFollowRedirects(false));
        client.requestAbs(HttpMethod.GET, oauth2Flow.getUserProfile)
              .putHeader("Accept", "application/json")
              .putHeader("Authorization", "Bearer " + hash.getString("access_token"))
              .putHeader("scope", oauth2Flow.scopeString(","))
              .as(BodyCodec.jsonObject())
              .send(cr -> {
                  client.close();
                  if (cr.succeeded()) {
                      HttpResponse<JsonObject> response = cr.result();
                      if (response.statusCode() == HttpResponseStatus.OK.code()) {
                          final JsonObject body = response.body();
                          logger.finest("body: " + body.encodePrettily());
                          final JsonObject state = new JsonObject(
                              base64.decode(context.session().remove("encodedState")));
                          final JsonObject loginState = state.getJsonObject("loginState");
                          String provider = loginState.getString("provider");
                          Map<String, Map<String, String>> providerMaps;
                          JsonObject providerAccount;
                          if ("AAC".equals(provider)) {
                              providerMaps = AAC_PROVIDER_MAPS;
                              final JsonObject aac = body.getJsonObject("accounts");
                              provider = aac.fieldNames().iterator().next();
                              providerAccount = aac.getJsonObject(provider);
                          } else {
                              providerMaps = PROVIDER_MAPS;
                              providerAccount = body;
                          }
                          final String email = providerMaps.get(provider).get(EMAIL);
                          // use email as account ID
                          final String id = providerAccount.getString(email);
                          if (id == null) {
                              context.fail(HttpResponseStatus.UNAUTHORIZED.code());
                              return;
                          }
                          final String firstName = providerMaps.get(provider).get(FIRST_NAME);
                          final String lastName = providerMaps.get(provider).get(LAST_NAME);
                          JsonObject account = new JsonObject();
                          account.put("id", id);
                          account.put(FIRST_NAME, providerAccount.getString(firstName, "Guest").trim());
                          account.put(LAST_NAME, providerAccount.getString(lastName, "").trim());
                          // generate displayName if it does not exists
                          account.put(DISPLAY_NAME,
                              (account.getString(FIRST_NAME) + " " + account.getString(LAST_NAME))
                                  .trim());
                          // create user
                          hash.put("scope", oauth2Flow.scope).put("loginState", loginState);
                          // set user account
                          user.principal().put("account", account);
                          // set user roles
                          readOrCreateUser(account, roles -> {
                              if (roles.succeeded()) {
                                  // respond
                                  logger.finest("oauth2 implicit flow user principal: "
                                      + user.principal().encodePrettily());
                                  new JsonResponse(context).end(user.principal());
                              } else {
                                  context.fail(roles.cause());
                              }
                          });
                      } else {
                          context.setUser(null);
                          context.fail(ServerError.message("error while fetching user account"));
                      }
                  } else {
                      context.setUser(null);
                      context.fail(cr.cause());
                  }
              });
    }

}
