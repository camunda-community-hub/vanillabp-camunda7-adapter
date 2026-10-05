package io.vanillabp.camunda7.it;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import io.vanillabp.spi.service.MultiInstanceElementResolver;

/**
 * Writes down every level the user task was told about, in the order it arrived. A resolver
 * rather than one parameter per level, because a case which loses the chain reports nothing
 * at all and a named parameter could not say so: it would refuse the call instead.
 */
@Component
public class MiUserTaskChainResolver implements MultiInstanceElementResolver<MiUserTaskAggregate, String> {

  /** What this resolver reads, which is the subprocess and the user task inside it. */
  @Override
  public Collection<String> getNames() {

    return List.of("MUT_Group", "MUT_Review");

  }

  @Override
  public String resolve(
      final MiUserTaskAggregate workflowAggregate,
      final Map<String, MultiInstance<Object>> multiInstances) {

    if (multiInstances.isEmpty()) {
      return "nothing";
    }
    return multiInstances
        .entrySet()
        .stream()
        .map(
            level -> "%s:%s#%d/%d"
                .formatted(
                    level.getKey(),
                    level.getValue().getElement(),
                    level.getValue().getIndex(),
                    level.getValue().getTotal()))
        .collect(Collectors.joining(">"));

  }

}
