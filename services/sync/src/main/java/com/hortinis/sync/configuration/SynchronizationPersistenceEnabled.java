package com.hortinis.sync.configuration;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Enables synchronization persistence and its dependent entry points by default. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ConditionalOnProperty(
    prefix = "hortinis.sync.persistence",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public @interface SynchronizationPersistenceEnabled {}
