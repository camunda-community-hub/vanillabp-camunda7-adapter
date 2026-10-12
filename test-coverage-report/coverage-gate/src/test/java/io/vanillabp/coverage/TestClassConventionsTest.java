package io.vanillabp.coverage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.CoverageGate;
import io.vanillabp.integration.test.utils.MessageConventions;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.TestClassConventions;

/**
 * A gate in the module which already gates this repository as a whole. Every test class
 * registers {@link SuppressOutputExtension}. So a build log carries what a FAILING test
 * printed, and nothing else.
 * <p>
 * Four classes of this repository did not. All four were quiet at the time, so nobody
 * noticed. The rule had been written down twice before it drifted. So it is checked here,
 * not left to a review.
 * <p>
 * It also checks the guiding messages of this repository. A developer reads a message in
 * the moment something goes wrong. A sentence which fell apart in the source takes away
 * the one explanation they get. The check reads the main sources, and one run of it covers
 * every module.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TestClassConventionsTest {

  @Test
  @DisplayName("Every test class of this repository suppresses its output")
  public void everyTestClassSuppressesItsOutput() {

    final var root = CoverageGate.repositoryRoot("coverage.repository.root");

    final var offenders = TestClassConventions.testClassesWithoutOutputSuppression(root);

    assertTrue(
        offenders.isEmpty(),
        () -> TestClassConventions.describeTestClassesWithoutOutputSuppression(offenders));

  }

  @Test
  @DisplayName("No test class registers the suppression after '@Testcontainers'")
  public void noTestClassSuppressesTooLate() {

    final var root = CoverageGate.repositoryRoot("coverage.repository.root");

    final var offenders = TestClassConventions.testClassesSuppressingTooLate(root);

    assertTrue(
        offenders.isEmpty(),
        () -> TestClassConventions.describeTestClassesSuppressingTooLate(offenders));

  }

  @Test
  @DisplayName("No message of this repository carries a run of spaces between two words")
  public void noMessageFellApart() {

    final var root = CoverageGate.repositoryRoot("coverage.repository.root");

    final var offenders = MessageConventions.messagesPulledApart(root);

    assertTrue(
        offenders.isEmpty(),
        () -> MessageConventions.describeMessagesPulledApart(offenders));

  }

  @Test
  @DisplayName("No message of this repository glues two words into one")
  public void noMessageIsGluedTogether() {

    final var root = CoverageGate.repositoryRoot("coverage.repository.root");

    final var offenders = MessageConventions.messagesGluedTogether(root);

    assertTrue(
        offenders.isEmpty(),
        () -> MessageConventions.describeMessagesGluedTogether(offenders));

  }

}
