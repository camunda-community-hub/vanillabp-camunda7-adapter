package io.vanillabp.camunda7.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the test of the viewer and history API. It has a class of its own,
 * because the test asks the viewer API on the process service of the process it views
 * ({@code ViewerParentProcess}).
 */
@Entity
@Table(name = "C7_VIEWER_AGGREGATE")
@Getter
@Setter
public class ViewerTestAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String content;

}
