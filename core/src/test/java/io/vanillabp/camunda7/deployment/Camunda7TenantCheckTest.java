package io.vanillabp.camunda7.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What VanillaBP can say about a Camunda 7 tenant: the engine needs none to exist, so
 * the only thing worth reporting is a tenant missing in an identity service which knows
 * others.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7TenantCheckTest {

  private static org.camunda.bpm.engine.IdentityService identityServiceKnowing(
      final String... tenantIds) {

    // the tenants are stubbed FIRST: stubbing a mock inside an unfinished stubbing of
    // another one is what Mockito rejects as unfinished stubbing
    final var tenants = Arrays
        .stream(tenantIds)
        .map(tenantId -> {
          final var tenant = Mockito.mock(org.camunda.bpm.engine.identity.Tenant.class);
          Mockito
              .lenient()
              .when(tenant.getId())
              .thenReturn(tenantId);
          return tenant;
        })
        .toList();

    final var identityService = Mockito.mock(org.camunda.bpm.engine.IdentityService.class);
    final var query = Mockito.mock(org.camunda.bpm.engine.identity.TenantQuery.class);
    Mockito
        .lenient()
        .when(identityService.createTenantQuery())
        .thenReturn(query);
    Mockito
        .lenient()
        .when(query.list())
        .thenReturn(tenants);
    return identityService;

  }

  /**
   * The WARNs the check logged (the module's logback-test.xml has no appender on
   * purpose).
   */
  private static List<String> warningsOf(
      final Runnable action) {

    final var logWatcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logWatcher.start();
    final var checkLog = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
        .getLogger(Camunda7TenantCheck.class);
    checkLog.addAppender(logWatcher);
    try {
      action.run();
    } finally {
      checkLog.detachAndStopAllAppenders();
    }
    return logWatcher.list
        .stream()
        .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
        .toList();

  }

  @Test
  @DisplayName("A tenant missing among REGISTERED tenants is reported")
  public void unregisteredTenantIsReported() {

    final var warnings = warningsOf(
        () -> Camunda7TenantCheck.warnAboutUnregisteredTenant(
            "myengine",
            "loan-approval",
            "loan-approval",
            identityServiceKnowing("banking", "insurance")));

    assertEquals(1, warnings.size(), warnings::toString);
    final var message = warnings.getFirst();
    assertTrue(message.contains("'loan-approval'"), () -> message);
    assertTrue(message.contains("banking"), () -> message);
    assertTrue(message.contains("creates nothing"), () -> message);
    assertTrue(message.contains("vanillabp.adapters.myengine.tenant-id"), () -> message);

  }

  @Test
  @DisplayName("An identity service without tenants says nothing - most applications register none")
  public void noRegisteredTenantsStaySilent() {

    assertEquals(
        List.of(),
        warningsOf(
            () -> Camunda7TenantCheck.warnAboutUnregisteredTenant(
                "myengine",
                "loan-approval",
                "loan-approval",
                identityServiceKnowing())));

    // ... as does a registered tenant
    assertEquals(
        List.of(),
        warningsOf(
            () -> Camunda7TenantCheck.warnAboutUnregisteredTenant(
                "myengine",
                "loan-approval",
                "loan-approval",
                identityServiceKnowing("loan-approval", "banking"))));

  }

  @Test
  @DisplayName("An identity provider refusing tenant queries never breaks a deployment")
  public void queryFailuresAreSwallowed() {

    final var refusing = Mockito.mock(org.camunda.bpm.engine.IdentityService.class);
    Mockito
        .when(refusing.createTenantQuery())
        .thenThrow(new UnsupportedOperationException("read-only identity provider"));

    assertEquals(
        List.of(),
        warningsOf(() -> Camunda7TenantCheck.warnAboutUnregisteredTenant("myengine", "loan-approval", "loan-approval",
            refusing)));
    // no identity service at all, and no tenant: nothing to check
    assertEquals(
        List.of(),
        warningsOf(
            () -> Camunda7TenantCheck.warnAboutUnregisteredTenant("myengine", "loan-approval", "loan-approval", null)));
    assertEquals(
        List.of(),
        warningsOf(
            () -> Camunda7TenantCheck.warnAboutUnregisteredTenant("myengine", "loan-approval", null,
                identityServiceKnowing("x"))));

  }

  /**
   * A runtime service answering how many instances of a process id there are, in total
   * and without a tenant. Both counts are what the check subtracts, so a double is more
   * honest here than an engine: the query is the whole mechanism.
   *
   * @param total Instances of the process id under any scope
   * @param withoutTenant Instances of it carrying no tenant
   * @param plainId Instances of the UNPREFIXED process id
   * @return The stubbed runtime service
   */
  private static org.camunda.bpm.engine.RuntimeService runtimeServiceWith(
      final long total,
      final long withoutTenant,
      final long plainId) {

    final var scopedQuery = Mockito.mock(org.camunda.bpm.engine.runtime.ProcessInstanceQuery.class);
    final var withoutTenantQuery = Mockito.mock(org.camunda.bpm.engine.runtime.ProcessInstanceQuery.class);
    final var plainQuery = Mockito.mock(org.camunda.bpm.engine.runtime.ProcessInstanceQuery.class);
    Mockito.lenient().when(scopedQuery.withoutTenantId()).thenReturn(withoutTenantQuery);
    Mockito.lenient().when(scopedQuery.count()).thenReturn(total);
    Mockito.lenient().when(withoutTenantQuery.count()).thenReturn(withoutTenant);
    Mockito.lenient().when(plainQuery.count()).thenReturn(plainId);

    final var runtimeService = Mockito.mock(org.camunda.bpm.engine.RuntimeService.class);
    final var query = Mockito.mock(org.camunda.bpm.engine.runtime.ProcessInstanceQuery.class);
    Mockito.lenient().when(runtimeService.createProcessInstanceQuery()).thenReturn(query);
    Mockito.lenient().when(query.processDefinitionKey("loan-approval-taken")).thenReturn(scopedQuery);
    Mockito.lenient().when(query.processDefinitionKey("taken")).thenReturn(plainQuery);
    return runtimeService;

  }

  @Test
  @DisplayName("Deploying into a tenant while the running workflows carry none is reported")
  public void workflowsWithoutTenantAreReported() {

    final var warnings = warningsOf(
        () -> Camunda7TenantCheck.warnAboutWorkflowsOutOfScope(
            "myengine",
            "loan-approval",
            "loan-approval-taken",
            "loan-approval-taken",
            "loan-approval",
            runtimeServiceWith(9, 9, 0)));

    assertEquals(1, warnings.size(), warnings::toString);
    final var message = warnings.getFirst();
    assertTrue(message.contains("9 of them carry no tenant"), () -> message);
    assertTrue(message.contains("'loan-approval'"), () -> message);
    assertTrue(message.contains("use-tenants: false"), () -> message);
    assertTrue(message.contains("name-clash-avoidance"), () -> message);

  }

  @Test
  @DisplayName("Deploying into no tenant while the running workflows carry one is reported")
  public void workflowsUnderATenantAreReported() {

    final var warnings = warningsOf(
        () -> Camunda7TenantCheck.warnAboutWorkflowsOutOfScope(
            "myengine",
            "loan-approval",
            "loan-approval-taken",
            "loan-approval-taken",
            null,
            runtimeServiceWith(9, 2, 0)));

    assertEquals(1, warnings.size(), warnings::toString);
    assertTrue(warnings.getFirst().contains("7 of them run under a tenant"), warnings::getFirst);

  }

  @Test
  @DisplayName("Workflows under the unprefixed process id are reported where the mode prefixes")
  public void workflowsUnderTheUnprefixedIdAreReported() {

    final var warnings = warningsOf(
        () -> Camunda7TenantCheck.warnAboutWorkflowsOutOfScope(
            "myengine",
            "loan-approval",
            "taken",
            "loan-approval-taken",
            null,
            runtimeServiceWith(0, 0, 4)));

    assertEquals(1, warnings.size(), warnings::toString);
    assertTrue(warnings.getFirst().contains("4 run under the unprefixed process id 'taken'"), warnings::getFirst);

  }

  @Test
  @DisplayName("A configuration which matches where the workflows run says nothing")
  public void aMatchingScopeIsQuiet() {

    assertEquals(
        List.of(),
        warningsOf(
            () -> Camunda7TenantCheck.warnAboutWorkflowsOutOfScope(
                "myengine",
                "loan-approval",
                "loan-approval-taken",
                "loan-approval-taken",
                "loan-approval",
                runtimeServiceWith(9, 0, 0))));

  }

  @Test
  @DisplayName("Without a runtime service, and where the engine refuses to answer, nothing is said")
  public void anUnanswerableEngineIsQuiet() {

    assertEquals(
        List.of(),
        warningsOf(
            () -> Camunda7TenantCheck.warnAboutWorkflowsOutOfScope(
                "myengine", "loan-approval", "p", "p", "loan-approval", null)));

    final var throwing = Mockito.mock(org.camunda.bpm.engine.RuntimeService.class);
    Mockito
        .when(throwing.createProcessInstanceQuery())
        .thenThrow(new IllegalStateException("no engine here"));
    assertEquals(
        List.of(),
        warningsOf(
            () -> Camunda7TenantCheck.warnAboutWorkflowsOutOfScope(
                "myengine", "loan-approval", "p", "p", "loan-approval", throwing)),
        "a diagnostic never fails a deployment");

  }

}
