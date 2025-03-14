package fr.axa.openpaas.dailyclean.service;

import fr.axa.openpaas.dailyclean.model.Workload;
import fr.axa.openpaas.dailyclean.util.KubernetesUtils;
import fr.axa.openpaas.dailyclean.util.NamespaceVerifier;
import fr.axa.openpaas.dailyclean.util.wrapper.DeploymentWrapper;
import fr.axa.openpaas.dailyclean.util.wrapper.StatefulSetWrapper;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.batch.v1.CronJob;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.ScalableResource;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.InternalServerErrorException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static fr.axa.openpaas.dailyclean.service.KubernetesArgument.START;
import static fr.axa.openpaas.dailyclean.service.KubernetesArgument.STOP;


@ApplicationScoped
public class KubernetesService {

    private static final Logger logger = LoggerFactory.getLogger(KubernetesService.class);

    @ConfigProperty(name = "service.job.imageName")
    String imgName;

    @ConfigProperty(name = "service.job.serviceAccountName")
    String serviceAccountName;

    @ConfigProperty(name = "service.job.timeZone")
    String timeZone;

    @ConfigProperty(name = "service.job.defaultCronStop")
    String defaultCronStop;

    @ConfigProperty(name = "service.deployment.label.dailyclean")
    String dailycleanLabelName;

    @ConfigProperty(name = "service.unauthorized.namespace.regex")
    Optional<String> unauthorizedNamespaceRegex;

    private final KubernetesClient kubernetesClient;

    public KubernetesService(final KubernetesClient kubernetesClient) {
        this.kubernetesClient = kubernetesClient;
    }

    /**
     * Create a Start CronJob.
     *
     * @param cron The cron given by the end user.
     */
    public void createStartCronJob(String cron) {
        Boolean suspended = KubernetesUtils.getCorrectSuspendValue(cron);
        String cronExpressionToApply = KubernetesUtils.geCorrectCronExpression(cron);

        createCronJob(cronExpressionToApply, suspended, START);
    }

    /**
     * Create a Stop CronJob.
     *
     * @param cron The cron given by the end user.
     */
    public void createStopCronJob(String cron) {
        Boolean suspended = KubernetesUtils.getCorrectSuspendValue(cron);
        String cronExpressionToApply = KubernetesUtils.geCorrectCronExpression(cron);

        createCronJob(cronExpressionToApply, suspended, STOP);
    }

    /**
     * Get the cron of an existing Start cronJob.
     *
     * @return The cron defined.
     */
    public String getCronStartAsString() {
        return getCronAsString(START);
    }

    /**
     * Get the cron of an existing Stop cronJob.
     *
     * @return The cron defined.
     */
    public String getCronStopAsString() {
        return getCronAsString(STOP);
    }

    /**
     * Delete all the existing CronJobs in the current project.
     */
    public void deleteCronJobs() {
        final String namespace = getNamespace();
        deleteCronJobIfExists(STOP, namespace);
        deleteCronJobIfExists(START, namespace);
    }

    /**
     * Create a new Start Job. Allows to start all the pods of the current project.
     */
    public void createStartJob() {
        createJob(START);
    }

    /**
     * Create a new Start Job. Allows to stop all the pods of the current project.
     */
    public void createStopJob() {
        createJob(STOP);
    }

    public String getNamespace() {
        return kubernetesClient.getNamespace();
    }

    /**
     * Get all deployments and statefulsets in the namespace
     * @return A list of workloads
     */
    public List<Workload> getWorkloads() {
        var apps = kubernetesClient.apps();
        var deployments = apps.deployments().inNamespace(getNamespace()).list()
                .getItems()
                .stream()
                .map(deployment -> KubernetesUtils.mapWorkload(new DeploymentWrapper(deployment), dailycleanLabelName))
                .collect(Collectors.toList());
        var statefulSets = apps.statefulSets().inNamespace(getNamespace()).list()
                .getItems()
                .stream()
                .map(statefulSet -> KubernetesUtils.mapWorkload(new StatefulSetWrapper(statefulSet), dailycleanLabelName))
                .collect(Collectors.toList());
        deployments.addAll(statefulSets);
        return deployments;
    }

    public void updatingCronJobIfNeeded() {
        CronJob start = getCronJob(START);
        CronJob stop = getCronJob(STOP);

        if(isUpdatingCronJobNeeded(start) || isUpdatingCronJobNeeded(stop)) {
            String startCronJobAsString = getCronAsStringFromCronJob(start);
            String stopCronJobAsString = getCronAsStringFromCronJob(stop);

            deleteCronJobs();

            createStartCronJob(startCronJobAsString);
            createStopCronJob(stopCronJobAsString);
        }
    }

    public void createDefaultStopCronJobIfNotExist() {
        CronJob stop = getCronJob(STOP);
        if(stop == null) {
            createCronJob(defaultCronStop, false, STOP);
        }
    }

    private boolean isUpdatingCronJobNeeded(CronJob cronJob) {
        boolean res = false;
        if(cronJob != null) {
            String cronImageName = getContainerImageName(cronJob);
            res = !imgName.equals(cronImageName);
        }
        return res;
    }

    private CronJob getCronJob(KubernetesArgument argument) {
        final String namespace = getNamespace();
        List<CronJob> cronJobs = kubernetesClient.batch().v1().cronjobs().inNamespace(namespace).list().getItems();
        return cronJobs.stream()
                .filter(cronJob -> KubernetesUtils.getCronName(argument).equals(cronJob.getMetadata().getName()))
                .findFirst()
                .orElse(null);
    }

    private String getCronAsString(KubernetesArgument argument) {
        CronJob job = getCronJob(argument);
        return getCronAsStringFromCronJob(job);
    }

    private String getCronAsStringFromCronJob(CronJob cronJob) {
        return cronJobIsNotSuspended(cronJob) ? cronJob.getSpec().getSchedule() : null;
    }

    private boolean cronJobIsNotSuspended(final CronJob cronJob) {
        return cronJob != null && !cronJob.getSpec().getSuspend();
    }

    private String getContainerImageName(CronJob cronJob) {
        String res = null;
        if(cronJob != null) {
            Container container = cronJob.getSpec()
                    .getJobTemplate()
                    .getSpec()
                    .getTemplate()
                    .getSpec()
                    .getContainers().stream().findFirst().orElseThrow();
            res = container.getImage();
        }
        return res;
    }

    private void createCronJob(String cron, Boolean suspend, KubernetesArgument argument) {
        assertImgNameIsNotBlanck();
        assertThatNamespaceIsAuthorized();

        final String namespace = getNamespace();

        logger.info("Creating cron job from object");
        kubernetesClient.batch().v1().cronjobs().inNamespace(namespace)
                .load(KubernetesUtils.createCronJobAsInputStream(argument, cron, imgName, serviceAccountName, timeZone, suspend))
                .createOrReplace();
        logger.info("Successfully created cronjob with name {}", KubernetesUtils.getCronName(argument));
    }

    private void createJob(KubernetesArgument argument) {
        assertImgNameIsNotBlanck();
        final String namespace = getNamespace();

        final String jobName = KubernetesUtils.getJobName(argument);

        ScalableResource<Job> jobResource = kubernetesClient.batch().jobs().inNamespace(namespace).withName(jobName);
        Job job = jobResource.get();
        if (job != null && job.getStatus() != null && StringUtils.isNotBlank(job.getStatus().getCompletionTime())) {
            jobResource.delete();
        }

        logger.info("Creating job from object");
        kubernetesClient.batch().jobs().inNamespace(namespace)
                .load(KubernetesUtils.createJobAsInputStream(argument, imgName, serviceAccountName))
                .createOrReplace();
        logger.info("Successfully created job with name {}", jobName);
    }

    private void assertImgNameIsNotBlanck() {
        if (StringUtils.isBlank(imgName)) {
            throw new InternalServerErrorException("The image name is not properly set.");
        }
    }

    private void assertThatNamespaceIsAuthorized() {
        final String currentNamespace = kubernetesClient.getNamespace();

        if(unauthorizedNamespaceRegex.isPresent() &&
                NamespaceVerifier.isNotAuthorize(currentNamespace, unauthorizedNamespaceRegex.get())){
            throw new InternalServerErrorException(
                    "Create CronJob action is not authorized for this namespace. Actual regex for unauthorized namespace : %s".formatted(unauthorizedNamespaceRegex.get()));
        }
    }

    private void deleteCronJobIfExists(KubernetesArgument argument, String namespace) {
        Resource<CronJob> cronJob =
                kubernetesClient.batch().v1().cronjobs()
                        .inNamespace(namespace).withName(KubernetesUtils.getCronName(argument));
        if (Boolean.TRUE.equals(cronJob.isReady())) {
            cronJob.delete();
        }
    }
}
