package io.vanillabp.camunda7.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the aggregate-sync integration test: it shares the
 * attributes it annotates, which DERIVES the class' mode "share nothing else" (opt-in).
 * <p>
 * Two expressions read it. The gateway of {@code SyncProcess} branches on
 * {@link #isApproved()}, which is NOT shared - that only works through the MIGRATION
 * FALLBACK of the EL resolver, which will be removed; the test pins that, including
 * the warning (see decision 1 in the repository's DECISIONS.md). The decision case - a task
 * computing what the gateway right behind it reads - lives in {@code DecisionTestAggregate}.
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
  @jakarta.persistence.SequenceGenerator(name = "c7SyncTestSeq", initialValue = 900000, allocationSize = 1)
  private Long id;

  /**
   * The only attribute Camunda 7 gets to see - as operator context.
   */
  @io.vanillabp.spi.service.SyncWithBPMS
  private String customerName;

  private boolean approved;

  private String secret;

  private String taskId;


}
