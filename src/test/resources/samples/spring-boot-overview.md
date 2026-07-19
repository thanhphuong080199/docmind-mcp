# Spring Boot Overview

Spring Boot makes it easy to create stand-alone, production-grade Spring applications
that you can just run. It takes an opinionated view of the Spring platform so you can
get started with minimum configuration.

## Auto-configuration

Auto-configuration attempts to automatically configure your Spring application based on
the jar dependencies you have added. For example, if HSQLDB is on your classpath and you
have not manually configured any database connection beans, Spring Boot auto-configures
an in-memory database. Auto-configuration classes are applied after user-defined beans
and back off when you define your own configuration.

## Starters

Starters are a set of convenient dependency descriptors you can include in your
application. You get a one-stop shop for all the Spring and related technology you need
without having to hunt through sample code and copy-paste loads of dependency
descriptors. For example, spring-boot-starter-data-jpa brings in everything needed for
Spring Data JPA.

## Actuator

The Actuator module provides production-ready features such as health checks, metrics,
and externalized HTTP endpoints for monitoring. Endpoints like /actuator/health and
/actuator/metrics let operators observe a running application without custom code.
