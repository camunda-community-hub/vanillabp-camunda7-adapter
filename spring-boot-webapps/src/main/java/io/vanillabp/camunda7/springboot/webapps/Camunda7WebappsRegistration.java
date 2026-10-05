package io.vanillabp.camunda7.springboot.webapps;

import static org.camunda.bpm.engine.authorization.Authorization.ANY;
import static org.camunda.bpm.engine.authorization.Authorization.AUTH_TYPE_GRANT;
import static org.camunda.bpm.engine.authorization.Groups.CAMUNDA_ADMIN;
import static org.camunda.bpm.engine.authorization.Permissions.ALL;

import java.util.ArrayList;
import java.util.List;

import org.camunda.bpm.container.RuntimeContainerDelegate;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.authorization.Groups;
import org.camunda.bpm.engine.authorization.Resources;
import org.camunda.bpm.engine.impl.persistence.entity.AuthorizationEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;

import io.vanillabp.camunda7.springboot.engine.Camunda7EngineHolder;
import io.vanillabp.camunda7.springboot.webapps.Camunda7WebappsProperties.AdminUser;

/**
 * Makes the engines VanillaBP built visible to Cockpit, Tasklist and Admin.
 *
 * <p>
 * The webapps do not look for {@code ProcessEngine} beans, they ask the
 * {@link RuntimeContainerDelegate} - and VanillaBP does not register its engines there,
 * because nothing in the adapter needs a global registry. This class registers every
 * engine whose adapter id has the webapps enabled, and removes it again when the context
 * stops, so a second context in the same JVM does not find a stale engine.
 * </p>
 *
 * <p>
 * The engine name is part of the webapps' URLs
 * (<code>/camunda/app/cockpit/vanillabp-camunda7-&lt;adapter-id&gt;/</code>), and
 * <code>/camunda</code> redirects to it. With several adapter ids the webapps offer all
 * of them in their engine switcher.
 * </p>
 */
public class Camunda7WebappsRegistration implements SmartLifecycle {

  private static final Logger logger = LoggerFactory.getLogger(Camunda7WebappsRegistration.class);

  private final ObjectProvider<Camunda7EngineHolder> engines;

  private final Camunda7WebappsProperties properties;

  private final List<ProcessEngine> registered = new ArrayList<>();

  private boolean running;

  /**
   * Remembers what it registered, so the webapps are left as they were found when the
   * application shuts down.
   *
   * @param engines The engines VanillaBP built, resolved lazily because they come into
   *          existence while the context refreshes
   * @param properties Which adapter ids the webapps serve, and who the administrator is
   */
  public Camunda7WebappsRegistration(
      final ObjectProvider<Camunda7EngineHolder> engines,
      final Camunda7WebappsProperties properties) {

    this.engines = engines;
    this.properties = properties;

  }

  @Override
  public void start() {

    engines
        .orderedStream()
        .forEach(holder -> {

          final var webapps = properties.webappsOf(holder.getAdapterId());
          if (!webapps.isEnabled()) {

            logger.debug(
                "Camunda7[{}]: the webapps are disabled for this adapter id",
                holder.getAdapterId());
            return;

          }

          final var engine = holder.getProcessEngine();
          final var container = RuntimeContainerDelegate.INSTANCE.get();
          if (container.getProcessEngineService().getProcessEngine(engine.getName()) == null) {
            container.registerProcessEngine(engine);
            registered.add(engine);
          }

          logger.info(
              "Camunda7[{}]: Cockpit, Tasklist and Admin serve the engine '{}'",
              holder.getAdapterId(),
              engine.getName());

          createAdminUser(holder.getAdapterId(), engine, webapps.getAdminUser());

        });

    running = true;

  }

  @Override
  public void stop() {

    final var container = RuntimeContainerDelegate.INSTANCE.get();
    registered.forEach(container::unregisterProcessEngine);
    registered.clear();
    running = false;

  }

  @Override
  public boolean isRunning() {

    return running;

  }

  @Override
  public int getPhase() {

    // after the engines were built and the workflow processing started
    return Integer.MAX_VALUE - 1;

  }

  /**
   * Creates the configured administrator, the group <code>camunda-admin</code> and the
   * grants that group needs, which is what the webapps expect to find - without a member
   * of that group they open their setup wizard instead of the login. The recipe is
   * Camunda's own, see their {@code CreateAdminUserConfiguration}.
   *
   * @param adapterId The adapter id whose engine is set up
   * @param engine    The engine
   * @param adminUser The configured administrator, or <code>null</code>
   */
  private void createAdminUser(
      final String adapterId,
      final ProcessEngine engine,
      final AdminUser adminUser) {

    if (adminUser == null) {
      return;
    }

    validate(adapterId, adminUser);

    final var identityService = engine.getIdentityService();
    if (identityService.createUserQuery().userId(adminUser.getId()).singleResult() != null) {

      logger.debug(
          "Camunda7[{}]: the user '{}' exists already",
          adapterId,
          adminUser.getId());
      return;

    }

    final var user = identityService.newUser(adminUser.getId());
    user.setPassword(adminUser.getPassword());
    user.setFirstName(adminUser.getFirstName());
    user.setLastName(adminUser.getLastName());
    user.setEmail(adminUser.getEmail());
    identityService.saveUser(user);

    createAdminGroup(identityService);
    grantAdminPermissions(engine);
    identityService.createMembership(adminUser.getId(), CAMUNDA_ADMIN);

    logger.info(
        """
            Camunda7[{}]: created the user '{}' as a member of '{}', which grants every \
            permission on everything this engine knows. It exists because \
            'vanillabp.adapters.{}.webapps.admin-user' says so - an application which \
            brings its own identity provider leaves that section out.""",
        adapterId,
        adminUser.getId(),
        CAMUNDA_ADMIN,
        adapterId);

  }

  private void validate(
      final String adapterId,
      final AdminUser adminUser) {

    if ((adminUser.getId() == null) || adminUser.getId().isBlank()) {

      throw new IllegalStateException(
          """
              The Camunda 7 adapter '%s' is asked to create an administrator for the \
              webapps, but 'vanillabp.adapters.%s.webapps.admin-user.id' is missing. Add \
              the user id, or remove the entire 'admin-user' section to have the webapps \
              show their setup wizard instead."""
              .formatted(adapterId, adapterId));

    }

    if ((adminUser.getPassword() == null) || adminUser.getPassword().isBlank()) {

      throw new IllegalStateException(
          """
              The Camunda 7 adapter '%s' is asked to create the administrator '%s' for the \
              webapps, but 'vanillabp.adapters.%s.webapps.admin-user.password' is missing. \
              Add the password, or remove the entire 'admin-user' section to have the \
              webapps show their setup wizard instead."""
              .formatted(adapterId, adminUser.getId(), adapterId));

    }

  }

  private void createAdminGroup(
      final IdentityService identityService) {

    if (identityService.createGroupQuery().groupId(CAMUNDA_ADMIN).count() > 0) {
      return;
    }

    final var group = identityService.newGroup(CAMUNDA_ADMIN);
    group.setName("camunda BPM Administrators");
    group.setType(Groups.GROUP_TYPE_SYSTEM);
    identityService.saveGroup(group);

  }

  private void grantAdminPermissions(
      final ProcessEngine engine) {

    final var authorizationService = engine.getAuthorizationService();

    for (final var resource : Resources.values()) {

      final var exists = authorizationService
          .createAuthorizationQuery()
          .groupIdIn(CAMUNDA_ADMIN)
          .resourceType(resource)
          .resourceId(ANY)
          .count() > 0;
      if (exists) {
        continue;
      }

      final var grant = new AuthorizationEntity(AUTH_TYPE_GRANT);
      grant.setGroupId(CAMUNDA_ADMIN);
      grant.setResource(resource);
      grant.setResourceId(ANY);
      grant.addPermission(ALL);
      authorizationService.saveAuthorization(grant);

    }

  }

}
