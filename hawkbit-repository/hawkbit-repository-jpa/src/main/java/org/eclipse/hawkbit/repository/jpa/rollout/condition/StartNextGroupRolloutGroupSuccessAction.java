/**
 * Copyright (c) 2015 Bosch Software Innovations GmbH and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.hawkbit.repository.jpa.rollout.condition;

import java.util.List;

import org.eclipse.hawkbit.repository.DeploymentManagement;
import org.eclipse.hawkbit.repository.jpa.RolloutGroupRepository;
import org.eclipse.hawkbit.repository.jpa.model.JpaRolloutGroup;
import org.eclipse.hawkbit.repository.model.Rollout;
import org.eclipse.hawkbit.repository.model.RolloutGroup;
import org.eclipse.hawkbit.repository.model.RolloutGroup.RolloutGroupStatus;
import org.eclipse.hawkbit.security.SystemSecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Success action which starts the next following {@link RolloutGroup}.
 */
public class StartNextGroupRolloutGroupSuccessAction implements RolloutGroupActionEvaluator<RolloutGroup.RolloutGroupSuccessAction> {

    private static final Logger logger = LoggerFactory.getLogger(StartNextGroupRolloutGroupSuccessAction.class);

    private final RolloutGroupRepository rolloutGroupRepository;

    private final DeploymentManagement deploymentManagement;

    private final SystemSecurityContext systemSecurityContext;

    public StartNextGroupRolloutGroupSuccessAction(final RolloutGroupRepository rolloutGroupRepository,
            final DeploymentManagement deploymentManagement, final SystemSecurityContext systemSecurityContext) {
        this.rolloutGroupRepository = rolloutGroupRepository;
        this.deploymentManagement = deploymentManagement;
        this.systemSecurityContext = systemSecurityContext;
    }

    @Override
    public RolloutGroup.RolloutGroupSuccessAction getAction() {
        return RolloutGroup.RolloutGroupSuccessAction.NEXTGROUP;
    }

    @Override
    public void exec(final Rollout rollout, final RolloutGroup rolloutGroup) {
        systemSecurityContext.runAsSystem(() -> {
            startNextGroup(rollout, rolloutGroup);
            return null;
        });
    }

    private void startNextGroup(final Rollout rollout, final RolloutGroup rolloutGroup) {
        // retrieve all actions according to the parent group of the finished
        // rolloutGroup, so retrieve all child-group actions which need to be
        // started.
        final long countOfStartedActions = deploymentManagement.startScheduledActionsByRolloutGroupParent(
                rollout.getId(), rollout.getDistributionSet().getId(), rolloutGroup.getId());
        logger.debug("{} Next actions started for rollout {} and parent group {}", countOfStartedActions, rollout,
                rolloutGroup);
        if (countOfStartedActions > 0) {
            // get all next scheduled groups and set them in state running
            rolloutGroupRepository.setStatusForCildren(RolloutGroupStatus.RUNNING, rolloutGroup);
        } else {
            logger.debug("No actions to start for next rolloutgroup of parent {} {}", rolloutGroup.getId(),
                    rolloutGroup.getName());
            // nothing for next group, just finish the group, this can happen
            // e.g. if targets has been deleted after the group has been
            // scheduled. If the group is empty now, we just finish the group if
            // there are not actions available for this group.
            final List<JpaRolloutGroup> findByRolloutGroupParent = rolloutGroupRepository
                    .findByParentIdAndStatus(rolloutGroup.getId(), RolloutGroupStatus.SCHEDULED);
            findByRolloutGroupParent.forEach(nextGroup -> {
                logger.debug("Rolloutgroup {} is finished, starting next group", nextGroup);
                nextGroup.setStatus(RolloutGroupStatus.FINISHED);
                rolloutGroupRepository.save(nextGroup);
                // find the next group to set in running state
                startNextGroup(rollout, nextGroup);
            });
        }
    }
}
