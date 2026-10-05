package io.vanillabp.camunda7.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the caller and of the called process of the call-by-expression test.
 * One class for both, which is what makes them one business case: a called process
 * working on the aggregate of its caller.
 */
@Entity
@Table(name = "C7_CALL_BY_EXPRESSION_AGGREGATE")
@Getter
@Setter
public class CallByExpressionAggregate {

  /**
   * An id range of its own: the Camunda business key is the aggregate's id, so
   * overlapping id spaces would let another test's business-key query match this
   * test's workflows.
   */
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "c7CallByExpressionSeq")
  @SequenceGenerator(name = "c7CallByExpressionSeq", initialValue = 940000, allocationSize = 1)
  private Long id;

  /**
   * Which of the two call activities the gateway sends the workflow to.
   */
  @io.vanillabp.spi.service.SyncWithBPMS
  private boolean callByExpression;

  /**
   * What the expression of the call activity reads: the id of the process to call.
   */
  @io.vanillabp.spi.service.SyncWithBPMS
  private String processToCall;

  /**
   * Written by the task of the CALLED process. The test reads it from the row the
   * caller created, so a value here says that the called process reached the
   * aggregate of its caller.
   */
  private String whatTheCalledProcessWrote;

}
