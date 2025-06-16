package org.miracum.etl.fhirgateway.processors;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.StrictErrorHandler;
import ca.uhn.fhir.validation.FhirValidator;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.PrePopulatedValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(
    name = "fhir.validation.enabled",
    havingValue = "true",
    matchIfMissing = false)
public class ValidationProcessor {
  private static final Logger LOG = LoggerFactory.getLogger(ValidationProcessor.class);

  private final FhirValidator validator;
  private final ValidationConfig config;

  public ValidationProcessor(FhirContext ctx, ValidationConfig config) {
    this.config = config;
    var pp = new PrePopulatedValidationSupport(ctx);

    var fhirPackageDir = config.packageBaseDirectoryPath();

    if (fhirPackageDir == null || !Files.exists(fhirPackageDir)) {
      LOG.warn("FHIR package base directory is unset or does not exist: {}", fhirPackageDir);
    } else {
      try (var stream = Files.list(fhirPackageDir)) {
        for (var packagePath : stream.toList()) {
          if (Files.isDirectory(packagePath)) {
            var pkg = NpmPackage.fromFolder(packagePath.toAbsolutePath().toString());
            var packageFolder = pkg.getFolders().get("package");

            for (var nextFile : packageFolder.listFiles()) {
              if (nextFile.endsWith(".json") && !nextFile.endsWith(".lock.json")) {
                var resourcePath = Path.of(packageFolder.getFolderPath(), nextFile);
                var input = Files.readString(resourcePath, StandardCharsets.UTF_8);
                var parser = ctx.newJsonParser();
                parser.setParserErrorHandler(new StrictErrorHandler());

                try {
                  var resource = parser.parseResource(input);
                  pp.addResource(resource);
                  LOG.debug(
                      "Added resource: {} from {}", resource.getIdElement().getValue(), nextFile);
                } catch (DataFormatException e) {
                  LOG.warn("Failed to parse resource from file: {}", nextFile, e);
                }
              }
            }
          }
        }
      } catch (IOException e) {
        LOG.error("Failed to process packages from folder", e);
        throw new RuntimeException("Failed to process packages from folder", e);
      }
    }

    // Create a validation support chain
    var validationSupportChain =
        new ValidationSupportChain(
            new DefaultProfileValidationSupport(ctx),
            new InMemoryTerminologyServerValidationSupport(ctx),
            new CommonCodeSystemsTerminologyService(ctx),
            pp);

    var instanceValidator = new FhirInstanceValidator(validationSupportChain);
    instanceValidator.setAnyExtensionsAllowed(config.anyExtensionAllowed());

    validator = ctx.newValidator();
    validator.registerValidatorModule(instanceValidator);

    if (config.concurrentBundleValidation().enabled()) {
      validator.setExecutorService(
          Executors.newFixedThreadPool(config.concurrentBundleValidation().maxThreadCount()));
      validator.setConcurrentBundleValidation(true);
    }
  }

  @Nullable
  public Bundle process(Bundle bundle) {

    // Validate
    var result = validator.validateWithResult(bundle);

    if (!result.isSuccessful()) {
      LOG.error("Validation failed.");
      var sb = new StringBuilder();
      sb.append("Validation failed with the following issues:\n");
      for (var next : result.getMessages()) {
        var msg =
            String.format(
                "Issue %s: %s - %s - %s%n",
                next.getMessageId(),
                next.getSeverity(),
                next.getLocationString(),
                next.getMessage());

        LOG.error(msg);
        sb.append(msg);
      }

      if (config.failOnError()) {
        throw new RuntimeException(sb.toString());
      }
    }

    // XXX: we could add a tag or something to the bundle to indicate it has been validated and when

    return bundle;
  }
}
