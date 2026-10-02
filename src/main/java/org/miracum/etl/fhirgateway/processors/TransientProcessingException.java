package org.miracum.etl.fhirgateway.processors;

/**
 * Processing a record failed for a reason that is expected to go away on its own, e.g. a downstream
 * service being temporarily unavailable, so the record should be retried instead of being sent to
 * the dead letter topic.
 */
public class TransientProcessingException extends RuntimeException {

  public TransientProcessingException(Throwable cause) {
    super(cause);
  }
}
