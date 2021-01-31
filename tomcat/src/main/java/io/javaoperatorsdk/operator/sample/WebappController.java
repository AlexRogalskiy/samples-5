package io.javaoperatorsdk.operator.sample;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Objects;

@Controller
public class WebappController implements ResourceController<Webapp> {

  private KubernetesClient kubernetesClient;

  private final Logger log = LoggerFactory.getLogger(getClass());

  public WebappController(KubernetesClient kubernetesClient) {
    this.kubernetesClient = kubernetesClient;
  }

  @Override
  public UpdateControl<Webapp> createOrUpdateResource(Webapp webapp, Context<Webapp> context) {
    if (Objects.equals(webapp.getSpec().getUrl(), webapp.getStatus().getDeployedArtifact())) {
      return UpdateControl.noUpdate();
    }

    String[] command = new String[] {"wget", "-O", "/data/" + webapp.getSpec().getContextPath() + ".war", webapp.getSpec().getUrl()};

    executeCommandInAllPods(kubernetesClient, webapp, command);

    webapp.getStatus().setDeployedArtifact(webapp.getSpec().getUrl());
    return UpdateControl.updateStatusSubResource(webapp);
  }

  @Override
  public DeleteControl deleteResource(Webapp webapp, Context<Webapp> context) {

    String[] command = new String[] {"rm", "/data/" + webapp.getSpec().getContextPath() + ".war"};
    executeCommandInAllPods(kubernetesClient, webapp, command);
    return DeleteControl.DEFAULT_DELETE;
  }

  private void executeCommandInAllPods(
      KubernetesClient kubernetesClient, Webapp webapp, String[] command) {
    Deployment deployment =
        kubernetesClient
            .apps()
            .deployments()
            .inNamespace(webapp.getMetadata().getNamespace())
            .withName(webapp.getSpec().getTomcat())
            .get();

    if (deployment != null) {
      List<Pod> pods =
          kubernetesClient
              .pods()
              .inNamespace(webapp.getMetadata().getNamespace())
              .withLabels(deployment.getSpec().getSelector().getMatchLabels())
              .list()
              .getItems();
      for (Pod pod : pods) {
        log.info(
            "Executing command {} in Pod {}",
            String.join(" ", command),
            pod.getMetadata().getName());
        kubernetesClient
            .pods()
            .inNamespace(deployment.getMetadata().getNamespace())
            .withName(pod.getMetadata().getName())
            .inContainer("war-downloader")
            .writingOutput(new ByteArrayOutputStream())
            .writingError(new ByteArrayOutputStream())
            .exec(command);
      }
    }
  }
}
