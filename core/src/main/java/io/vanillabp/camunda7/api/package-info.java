/**
 * What this adapter knows about its engine and hands out, for an extension running inside
 * the same engine.
 * <p>
 * Camunda 7 is embedded. An extension of this adapter runs in the same JVM, on the same
 * execution tree and against the same process definitions, so every fact it needs is a fact
 * the adapter already looked up. Where the adapter kept that lookup to itself, an extension
 * had to read the engine's internals a second time, and two readings of the same internals
 * drift apart on the next engine release.
 * <p>
 * This package is therefore API, not internals. What it promises and what it deliberately
 * does not promise is written on each type. It is Camunda 7 API of this repository, like
 * {@link io.vanillabp.camunda7.engine.Camunda7EngineCustomizer}, and it is not part of the
 * VanillaBP adapter SPI: nothing here is a mechanism another BPMS shares.
 * <p>
 * Why an extension uses the adapter's own API rather than the engine is decision 21 in the
 * repository's DECISIONS.md.
 */
package io.vanillabp.camunda7.api;
