package io.vanillabp.camunda7.it;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * One handler per cell of the parameter-type cases: the same five values of the
 * aggregate, declared once as a type which holds them and once as a type which does not.
 * A handler which is entered records what arrived; a handler whose conversion is refused
 * is never entered, and the test reads the incident instead.
 */
@Service
@WorkflowService(
    workflowAggregateClass = ParamTypesAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ParamTypes"))
public class ParamTypesWorkflowService {

  private final ProcessService<ParamTypesAggregate> processService;

  public ParamTypesWorkflowService(
      final ProcessService<ParamTypesAggregate> processService) {

    this.processService = processService;

  }

  public ParamTypesAggregate start(
      final ParamTypesAggregate aggregate) {

    return processService.startWorkflow(aggregate);

  }

  @WorkflowTask
  public void totalIntoDouble(
      final ParamTypesAggregate aggregate,
      @TaskParam("total") final Double total) {

    ParamTypesProbe.arrived("PT_total_Double", total);

  }

  @WorkflowTask
  public void totalIntoBigDecimal(
      final ParamTypesAggregate aggregate,
      @TaskParam("total") final BigDecimal total) {

    ParamTypesProbe.arrived("PT_total_BigDecimal", total);

  }

  @WorkflowTask
  public void totalIntoInt(
      final ParamTypesAggregate aggregate,
      @TaskParam("total") final int total) {

    ParamTypesProbe.arrived("PT_total_int", total);

  }

  @WorkflowTask
  public void nestedTotalIntoDouble(
      final ParamTypesAggregate aggregate,
      @TaskParam("orderTotal") final Double orderTotal) {

    ParamTypesProbe.arrived("PT_nested_Double", orderTotal);

  }

  @WorkflowTask
  public void rateIntoDouble(
      final ParamTypesAggregate aggregate,
      @TaskParam("rate") final Double rate) {

    ParamTypesProbe.arrived("PT_rate_Double", rate);

  }

  @WorkflowTask
  public void countIntoLong(
      final ParamTypesAggregate aggregate,
      @TaskParam("count") final long count) {

    ParamTypesProbe.arrived("PT_count_long", count);

  }

  @WorkflowTask
  public void countIntoInt(
      final ParamTypesAggregate aggregate,
      @TaskParam("count") final int count) {

    ParamTypesProbe.arrived("PT_count_int", count);

  }

  @WorkflowTask
  public void hugeIntoLong(
      final ParamTypesAggregate aggregate,
      @TaskParam("huge") final long huge) {

    ParamTypesProbe.arrived("PT_huge_long", huge);

  }

  @WorkflowTask
  public void hugeIntoDouble(
      final ParamTypesAggregate aggregate,
      @TaskParam("huge") final Double huge) {

    ParamTypesProbe.arrived("PT_huge_Double", huge);

  }

  @WorkflowTask
  public void textIntoInt(
      final ParamTypesAggregate aggregate,
      @TaskParam("totalText") final int totalText) {

    ParamTypesProbe.arrived("PT_text_int", totalText);

  }

}
