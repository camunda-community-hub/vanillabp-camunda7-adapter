package io.vanillabp.camunda7.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the aggregate-sync integration test. It shares the attributes it
 * annotates. That DERIVES the mode of the class: "share nothing else" (opt-in).
 * <p>
 * Two expressions read it. The gateway of {@code SyncProcess} branches on
 * {@link #isApproved()}, which is NOT shared. That only works through the MIGRATION FALLBACK of
 * the EL resolver, which will be removed (see decision 1 in the repository's DECISIONS.md). The
 * test pins that the fallback answers. It does not check the warning the fallback prints.
 * {@code AbstractNestedExpressionsIT} checks that warning, for a model of its own. The decision case lives in
 * {@code DecisionTestAggregate}: a task computes what the gateway right behind it reads.
 */
@Entity
@Table(name = "C7_SYNC_TEST_AGGREGATE")
@Getter
@Setter
public class SyncTestAggregate {

  /**
   * A HIGH id range on purpose: the Camunda business key is the aggregate's id, and
   * the id spaces of the different test aggregates would otherwise overlap - a
   * business-key query of another test would then match this test's workflows.
   */
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "c7SyncTestSeq")
  @jakarta.persistence.SequenceGenerator(name = "c7SyncTestSeq", initialValue = 910000, allocationSize = 1)
  private Long id;

  /**
   * The only attribute which Camunda 7 gets to see, as context for the operator.
   */
  @io.vanillabp.spi.service.SyncWithBPMS
  private String customerName;

  private boolean approved;

  private String secret;

  private String taskId;


}
