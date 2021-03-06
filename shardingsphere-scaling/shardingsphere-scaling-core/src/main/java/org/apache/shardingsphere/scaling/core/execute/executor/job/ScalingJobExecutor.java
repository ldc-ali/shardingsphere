/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.scaling.core.execute.executor.job;

import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.elasticjob.infra.pojo.JobConfigurationPOJO;
import org.apache.shardingsphere.elasticjob.lite.api.bootstrap.impl.OneOffJobBootstrap;
import org.apache.shardingsphere.elasticjob.lite.internal.election.LeaderService;
import org.apache.shardingsphere.elasticjob.lite.lifecycle.api.JobAPIFactory;
import org.apache.shardingsphere.elasticjob.reg.base.CoordinatorRegistryCenter;
import org.apache.shardingsphere.governance.repository.api.config.GovernanceConfiguration;
import org.apache.shardingsphere.governance.repository.api.listener.DataChangedEvent;
import org.apache.shardingsphere.scaling.core.api.RegistryRepositoryAPI;
import org.apache.shardingsphere.scaling.core.api.ScalingAPIFactory;
import org.apache.shardingsphere.scaling.core.config.JobConfiguration;
import org.apache.shardingsphere.scaling.core.config.ScalingContext;
import org.apache.shardingsphere.scaling.core.constant.ScalingConstant;
import org.apache.shardingsphere.scaling.core.execute.executor.AbstractScalingExecutor;
import org.apache.shardingsphere.scaling.core.execute.executor.ScalingExecutor;
import org.apache.shardingsphere.scaling.core.job.ScalingJob;
import org.apache.shardingsphere.scaling.core.utils.ElasticJobUtil;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scaling job executor.
 */
@Slf4j
public final class ScalingJobExecutor extends AbstractScalingExecutor implements ScalingExecutor {
    
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    
    private static final Pattern CONFIG_PATTERN = Pattern.compile("(\\d+)/config");
    
    private final RegistryRepositoryAPI registryRepositoryAPI = ScalingAPIFactory.getRegistryRepositoryAPI();
    
    private final Map<String, JobBootstrapWrapper> scalingJobBootstrapMap = Maps.newHashMap();
    
    private GovernanceConfiguration governanceConfig;
    
    private CoordinatorRegistryCenter registryCenter;
    
    @Override
    public void start() {
        super.start();
        log.info("Start scaling job executor.");
        this.governanceConfig = ScalingContext.getInstance().getServerConfig().getGovernanceConfig();
        registryCenter = ElasticJobUtil.createRegistryCenter();
        watchConfigRepository();
    }
    
    private void watchConfigRepository() {
        registryRepositoryAPI.watch(ScalingConstant.SCALING_ROOT, event -> {
            Optional<JobConfiguration> jobConfig = getJobConfig(event);
            if (!jobConfig.isPresent()) {
                return;
            }
            switch (event.getType()) {
                case ADDED:
                case UPDATED:
                    executeJob(getJobId(event.getKey()), jobConfig.get());
                    break;
                case DELETED:
                    deleteJob(getJobId(event.getKey()), jobConfig.get());
                    break;
                default:
                    break;
            }
        });
    }
    
    private String getJobId(final String key) {
        Matcher matcher = CONFIG_PATTERN.matcher(key);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return key.split("/")[2];
    }
    
    private Optional<JobConfiguration> getJobConfig(final DataChangedEvent event) {
        if (!CONFIG_PATTERN.matcher(event.getKey()).matches()) {
            return Optional.empty();
        }
        try {
            log.info("{} job config: {} = {}", event.getType(), event.getKey(), event.getValue());
            return Optional.of(GSON.fromJson(event.getValue(), JobConfiguration.class));
        } catch (JsonSyntaxException ex) {
            log.error("analyze job config failed.", ex);
        }
        return Optional.empty();
    }
    
    private void executeJob(final String jobId, final JobConfiguration jobConfig) {
        JobBootstrapWrapper jobBootstrapWrapper = scalingJobBootstrapMap.get(jobId);
        if (null == jobBootstrapWrapper) {
            createJob(jobId, jobConfig);
            return;
        }
        updateJob(jobId, jobConfig);
    }
    
    private void createJob(final String jobId, final JobConfiguration jobConfig) {
        if (jobConfig.getHandleConfig().isRunning()) {
            JobBootstrapWrapper jobBootstrapWrapper = new JobBootstrapWrapper(jobId, jobConfig);
            jobBootstrapWrapper.getJobBootstrap().execute();
            scalingJobBootstrapMap.put(jobId, jobBootstrapWrapper);
        }
    }
    
    private void updateJob(final String jobId, final JobConfiguration jobConfig) {
        JobBootstrapWrapper jobBootstrapWrapper = scalingJobBootstrapMap.get(jobId);
        if (jobBootstrapWrapper.isRunning() && jobConfig.getHandleConfig().isRunning()) {
            log.warn("scaling elastic job has already running, ignore current config.");
            return;
        }
        if (jobBootstrapWrapper.isRunning() == jobConfig.getHandleConfig().isRunning()) {
            return;
        }
        if (new LeaderService(registryCenter, jobId).isLeader()) {
            log.info("leader worker update config.");
            JobAPIFactory.createJobConfigurationAPI(governanceConfig.getRegistryCenterConfiguration().getServerLists(),
                    governanceConfig.getName() + ScalingConstant.SCALING_ROOT, null)
                    .updateJobConfiguration(JobConfigurationPOJO.fromJobConfiguration(createJobConfig(jobId, jobConfig)));
        }
        jobBootstrapWrapper.setRunning(jobConfig.getHandleConfig().isRunning());
        jobBootstrapWrapper.getJobBootstrap().execute();
    }
    
    private org.apache.shardingsphere.elasticjob.api.JobConfiguration createJobConfig(final String jobId, final JobConfiguration jobConfig) {
        return org.apache.shardingsphere.elasticjob.api.JobConfiguration.newBuilder(jobId, jobConfig.getHandleConfig().getShardingTables().length)
                .jobParameter(GSON.toJson(jobConfig)).overwrite(true).build();
    }
    
    private void deleteJob(final String jobId, final JobConfiguration jobConfig) {
        jobConfig.getHandleConfig().setRunning(false);
        executeJob(jobId, jobConfig);
    }
    
    @Getter
    @Setter
    private final class JobBootstrapWrapper {
        
        private final OneOffJobBootstrap jobBootstrap;
        
        private boolean running;
        
        private JobBootstrapWrapper(final String jobId, final JobConfiguration jobConfig) {
            jobBootstrap = new OneOffJobBootstrap(registryCenter, new ScalingJob(), createJobConfig(jobId, jobConfig));
            running = jobConfig.getHandleConfig().isRunning();
        }
    }
}
