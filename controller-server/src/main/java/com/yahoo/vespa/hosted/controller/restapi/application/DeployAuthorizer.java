// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.restapi.application;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.Environment;
import com.yahoo.vespa.hosted.controller.api.Tenant;
import com.yahoo.vespa.hosted.controller.api.identifiers.AthensDomain;
import com.yahoo.vespa.hosted.controller.api.identifiers.ScrewdriverId;
import com.yahoo.vespa.hosted.controller.api.identifiers.UserId;
import com.yahoo.vespa.hosted.controller.api.integration.athens.ApplicationAction;
import com.yahoo.vespa.hosted.controller.api.integration.athens.Athens;
import com.yahoo.vespa.hosted.controller.api.integration.athens.AthensPrincipal;
import com.yahoo.vespa.hosted.controller.api.integration.athens.ZmsException;
import com.yahoo.vespa.hosted.controller.api.integration.zone.ZoneRegistry;
import com.yahoo.vespa.hosted.controller.restapi.filter.UnauthenticatedUserPrincipal;

import javax.ws.rs.ForbiddenException;
import java.security.Principal;
import java.util.Optional;
import java.util.logging.Logger;

import static com.yahoo.vespa.hosted.controller.restapi.application.Authorizer.environmentRequiresAuthorization;
import static com.yahoo.vespa.hosted.controller.restapi.application.Authorizer.isScrewdriverPrincipal;

/**
 * @author bjorncs
 * @author gjoranv
 */
public class DeployAuthorizer {

    private static final Logger log = Logger.getLogger(DeployAuthorizer.class.getName());

    private final Athens athens;
    private final ZoneRegistry zoneRegistry;

    public DeployAuthorizer(Athens athens, ZoneRegistry zoneRegistry) {
        this.athens = athens;
        this.zoneRegistry = zoneRegistry;
    }

    public void throwIfUnauthorizedForDeploy(Principal principal,
                                             Environment environment,
                                             Tenant tenant,
                                             ApplicationId applicationId) {
        if (athensCredentialsRequired(environment, tenant, applicationId, principal))
            checkAthensCredentials(principal, tenant, applicationId);
    }

    // TODO: inline when deployment via ssh is removed
    private boolean athensCredentialsRequired(Environment environment, Tenant tenant, ApplicationId applicationId, Principal principal) {
        if (!environmentRequiresAuthorization(environment))  return false;

        if (! isScrewdriverPrincipal(athens, principal))
            throw loggedForbiddenException(
                    "Principal '%s' is not a screwdriver principal, and does not have deploy access to application '%s'",
                    principal.getName(), applicationId.toShortString());

        return tenant.isAthensTenant();
    }


    // TODO: inline when deployment via ssh is removed
    private void checkAthensCredentials(Principal principal, Tenant tenant, ApplicationId applicationId) {
        AthensDomain domain = tenant.getAthensDomain().get();
        if (! (principal instanceof AthensPrincipal))
            throw loggedForbiddenException("Principal '%s' is not authenticated.", principal.getName());

        AthensPrincipal athensPrincipal = (AthensPrincipal)principal;
        if ( ! hasDeployAccessToAthensApplication(athensPrincipal, domain, applicationId))
            throw loggedForbiddenException(
                    "Screwdriver principal '%1$s' does not have deploy access to '%2$s'. " +
                    "Either the application has not been created at " + zoneRegistry.getDashboardUri() + " or " +
                    "'%1$s' is not added to the application's deployer role in Athens domain '%3$s'.",
                    athensPrincipal, applicationId, tenant.getAthensDomain().get());
    }

    private static ForbiddenException loggedForbiddenException(String message, Object... args) {
        String formattedMessage = String.format(message, args);
        log.info(formattedMessage);
        return new ForbiddenException(formattedMessage);
    }

    /**
     * @deprecated Only usable for ssh. Use the method that takes Principal instead of UserId and screwdriverId.
     */
    @Deprecated
    public void throwIfUnauthorizedForDeploy(Environment environment,
                                             UserId userId,
                                             Tenant tenant,
                                             ApplicationId applicationId,
                                             Optional<ScrewdriverId> optionalScrewdriverId) {

        Principal principal = new UnauthenticatedUserPrincipal(userId.id());

        if (athensCredentialsRequired(environment, tenant, applicationId, principal)) {
            ScrewdriverId screwdriverId = optionalScrewdriverId.orElseThrow(
                    () -> loggedForbiddenException("Screwdriver id must be provided when deploying from Screwdriver."));
            principal = athens.principalFrom(screwdriverId);
            checkAthensCredentials(principal, tenant, applicationId);
        }
    }

    private boolean hasDeployAccessToAthensApplication(AthensPrincipal principal, AthensDomain domain, ApplicationId applicationId) {
        try {
            return athens.zmsClientFactory().createClientWithServicePrincipal()
                    .hasApplicationAccess(
                            principal,
                            ApplicationAction.deploy,
                            domain,
                            new com.yahoo.vespa.hosted.controller.api.identifiers.ApplicationId(applicationId.application().value()));
        } catch (ZmsException e) {
            throw loggedForbiddenException(
                    "Failed to authorize deployment through Athens. If this problem persists, " +
                            "please create ticket at yo/vespa-support. (" + e.getMessage() + ")");
        }
    }
}
