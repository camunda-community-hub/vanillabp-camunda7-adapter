[![](https://img.shields.io/badge/Community%20Extension-An%20open%20source%20community%20maintained%20project-FF4700)](https://github.com/camunda-community-hub/community)
![Compatible with: Camunda Platform 7](https://img.shields.io/badge/Compatible%20with-Camunda%20Platform%207-26d07c)

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.camunda.community.vanillabp/camunda7-adapter/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.camunda.community.vanillabp/camunda7-adapter)
[![](https://img.shields.io/badge/Lifecycle-Incubating-blue)](https://github.com/Camunda-Community-Hub/community/blob/main/extension-lifecycle.md#incubating-)
[![Apache License V.2](https://img.shields.io/badge/license-Apache%20V.2-blue.svg)](./LICENSE)

![VanillaBP](./readme/vanillabp-headline.png)

# Camunda 7 adapter

This is an adapter which implements the binding of the [VanillaBP SPI](https://github.com/vanillabp/spi-for-java) in order to run business processes using [Camunda 7](https://docs.camunda.org).

If you are interested in migrating to [Camunda 8](https://docs.camunda.io) then checkout the [drop-in replacement adapter for Camunda 8](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter).

## Runtime environments

Currently, only Spring Boot is supported by including this Maven dependency:

```xml
<dependency>
  <groupId>org.camunda.community.vanillabp</groupId>
  <artifactId>camunda7-spring-boot-adapter</artifactId>
</dependency>
```

To learn about details of the adapter checkout the [module's README](./spring-boot/README.md). 

## How it looks like

This is a section of a taxi ride workflow and should give you an idea of how the VanillaBP SPI is used in your business code. To learn more about VanillaBP checkout the [SPI documentation](https://github.com/vanillabp/spi-for-java).

![Section of a taxi ride workflow](./readme/example.png) *Screenshot of [Camunda Modeler](https://camunda.com/en/download/modeler/)*

```java
@Service
@WorkflowService(workflowAggregateClass = Ride.class)
@Transactional(noRollbackFor = TaskException.class)
public class TaxiRide {
    
    @Autowired
    private ProcessService<Ride> processService;
    
    public String rideBooked(
            final Location pickupLocation,
            final OffsetDateTime pickupTime,
            final Location targetLocation) {
        
        final var ride = new Ride();
        ...
        // start the workflow by correlation of the message start event
        return processService
                .correlateMessage(ride, "RideBooked")
                .getRideId();
    }
    
    @WorkflowTask
    public void determinePotentialDrivers(
            final Ride ride) {
        
        final var parameters = new DriversNearbyParameters()
                .longitude(ride.getPickupLocation().getLongitude())
                .latitude(ride.getPickupLocation().getLatitude());

        final var potentialDrivers = driverService
                .determineDriversNearby(parameters);

        ride.setPotentialDrivers(
                mapper.toDomain(potentialDrivers, ride));
    }

    @WorkflowTask
    public void requestRideOfferFromDriver(
            final Ride ride,
            @MultiInstanceIndex("RequestRideOffer")
            final int potentialDriverIndex) {
        
        final var driver = ride.getPotentialDrivers().get(potentialDriverIndex);
        
        driverService.requestRideOffer(
                driver.getId(),
                new RequestRideOfferParameters()
                        .rideId(ride.getRideId())
                        .pickupLocation(mapper.toApi(ride.getPickupLocation()))
                        .pickupTime(ride.getPickupTime())
                        .targetLocation(mapper.toApi(ride.getTargetLocation())));
        
    }
    ...
```

## Noteworthy & Contributors

VanillaBP was developed by [Phactum](https://www.phactum.at) with the intention of giving back to the community as it has benefited the community in the past.

![Phactum](./readme/phactum.png)

## License

Copyright 2022 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
