package com.aka.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import jakarta.servlet.ServletException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.realm.GenericPrincipal;
import org.apache.catalina.valves.ValveBase;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SecurityValve extends ValveBase {

    /*
     * Configuration de notre fournisseur d'identité.
     *
     * Dans une étape ultérieure, ces valeurs ne devront probablement
     * plus être codées en dur dans la classe.
     */
    private static final String ISSUER =
            "http://localhost:8080/realms/tomcat-platform";

    private static final String AUDIENCE =
            "golden-image";

    private static final String JWKS_URI =
            "http://host.docker.internal:8080/realms/tomcat-platform/protocol/openid-connect/certs";


    @Override
    public void invoke(Request request, Response response)
            throws IOException {

        /*
         * ---------------------------------------------------------
         * 1. Récupération du header Authorization
         * ---------------------------------------------------------
         *
         * Le client doit envoyer :
         *
         * Authorization: Bearer eyJhbGciOiJSUzI1Ni...
         */
        String authorization = request.getHeader("Authorization");

        if (authorization == null || !authorization.startsWith("Bearer ")) {
            response.sendError(401, "Missing Bearer token");
            return;
        }


        /*
         * ---------------------------------------------------------
         * 2. Extraction du JWT
         * ---------------------------------------------------------
         *
         * On retire simplement le préfixe "Bearer ".
         *
         * ATTENTION :
         * à ce stade le token n'est PAS encore considéré comme fiable.
         */
        String token = authorization
                .substring("Bearer ".length())
                .trim();

        if (token.isEmpty()) {
            response.sendError(401, "Missing Bearer token");
            return;
        }


        try {

            /*
             * ---------------------------------------------------------
             * 3. Configuration du JWKS
             * ---------------------------------------------------------
             *
             * Keycloak publie ses clés publiques via son endpoint JWKS.
             *
             * Le JWT contient notamment :
             *
             * {
             *   "alg": "RS256",
             *   "kid": "..."
             * }
             *
             * Nimbus pourra utiliser le kid pour retrouver
             * la bonne clé publique dans le JWKS.
             */
            URL jwksUrl = URI.create(JWKS_URI).toURL();

            JWKSource<SecurityContext> keySource =
                    JWKSourceBuilder
                            .create(jwksUrl)
                            .build();


            /*
             * ---------------------------------------------------------
             * 4. Configuration de la vérification de signature
             * ---------------------------------------------------------
             *
             * Nous n'acceptons ici que des JWT signés avec RS256.
             */
            JWSKeySelector<SecurityContext> keySelector =
                    new JWSVerificationKeySelector<>(
                            JWSAlgorithm.RS256,
                            keySource
                    );


            /*
             * ---------------------------------------------------------
             * 5. Création du processeur JWT Nimbus
             * ---------------------------------------------------------
             */
            ConfigurableJWTProcessor<SecurityContext> jwtProcessor =
                    new DefaultJWTProcessor<>();

            jwtProcessor.setJWSKeySelector(keySelector);


            /*
             * ---------------------------------------------------------
             * 6. Claims attendus
             * ---------------------------------------------------------
             *
             * Le token doit provenir de notre realm Keycloak.
             *
             * iss doit donc être :
             *
             * http://localhost:8080/realms/tomcat-platform
             */
            JWTClaimsSet exactMatchClaims =
                    new JWTClaimsSet.Builder()
                            .issuer(ISSUER)
                            .build();


            /*
             * ---------------------------------------------------------
             * 7. Validation des claims
             * ---------------------------------------------------------
             *
             * Nous vérifions notamment :
             *
             * aud -> golden-image
             * iss -> notre realm Keycloak
             * exp -> le token doit posséder une expiration valide
             * sub -> le token doit identifier un sujet
             */
            jwtProcessor.setJWTClaimsSetVerifier(
                    new DefaultJWTClaimsVerifier<>(
                            AUDIENCE,
                            exactMatchClaims,
                            Set.of("exp", "sub")
                    )
            );


            /*
             * ---------------------------------------------------------
             * 8. Validation effective du JWT
             * ---------------------------------------------------------
             *
             * C'est ici que Nimbus traite réellement le token.
             *
             * Il vérifie notamment :
             *
             * JWT
             *  |
             *  +-- kid
             *  |    |
             *  |    +--> JWKS
             *  |           |
             *  |           +--> clé publique
             *  |
             *  +-- signature RS256
             *  +-- issuer
             *  +-- audience
             *  +-- expiration
             *
             * Si une validation échoue, une exception est levée.
             */
            JWTClaimsSet claims =
                    jwtProcessor.process(token, null);


            /*
             * ---------------------------------------------------------
             * 9. Récupération de l'identité Keycloak
             * ---------------------------------------------------------
             *
             * Dans notre token :
             *
             * "preferred_username": "alice"
             */
            String username =
                    claims.getStringClaim("preferred_username");

            if (username == null || username.isBlank()) {
                response.sendError(401, "Missing username claim");
                return;
            }


            /*
             * ---------------------------------------------------------
             * 10. Récupération des Realm Roles
             * ---------------------------------------------------------
             *
             * Keycloak produit quelque chose comme :
             *
             * "realm_access": {
             *     "roles": [
             *         "ADMIN",
             *         "USER"
             *     ]
             * }
             */
            Map<String, Object> realmAccess =
                    (Map<String, Object>) claims.getClaim("realm_access");

            if (realmAccess == null) {
                response.sendError(401, "Missing realm_access claim");
                return;
            }

            List<String> roles =
                    (List<String>) realmAccess.get("roles");

            if (roles == null) {
                roles = List.of();
            }


            /*
             * ---------------------------------------------------------
             * 11. Création du Principal Tomcat
             * ---------------------------------------------------------
             *
             * Avant :
             *
             *   alice était codée en dur.
             *
             * Maintenant :
             *
             *   Keycloak
             *      |
             *      +--> JWT
             *             |
             *             +--> preferred_username = alice
             *             +--> roles = USER, ADMIN
             *                        |
             *                        v
             *                 GenericPrincipal
             */
            Principal principal =
                    new GenericPrincipal(
                            username,
                            roles
                    );

            request.setUserPrincipal(principal);


            /*
             * Header uniquement pratique pour notre laboratoire
             * afin de voir que la SecurityValve est passée.
             */
            response.setHeader(
                    "X-Security-Valve",
                    "active"
            );

            System.out.println(
                    "[SecurityValve] Authenticated user: "
                            + principal.getName()
                            + " roles=" + roles
            );


            /*
             * ---------------------------------------------------------
             * 12. Transmission de la requête au reste de Tomcat
             * ---------------------------------------------------------
             *
             * À partir d'ici, le WAR peut utiliser :
             *
             * request.getUserPrincipal()
             * request.getRemoteUser()
             * request.isUserInRole("USER")
             * request.isUserInRole("ADMIN")
             */
            getNext().invoke(request, response);

        } catch (Exception e) {

            /*
             * Signature invalide, token expiré,
             * mauvais issuer, mauvaise audience, etc.
             *
             * Dans tous ces cas :
             *
             *          401 Unauthorized
             */
            System.err.println(
                    "[SecurityValve] JWT validation failed: "
                            + e.getMessage()
            );

            response.sendError(
                    401,
                    "Invalid JWT"
            );
        }
    }
}