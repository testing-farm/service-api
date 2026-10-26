/*
 * Copyright 2021 EPAM Systems
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.epam.ta.reportportal.core.analyzer.auto.impl;

import static com.epam.reportportal.rules.commons.validation.BusinessRule.expect;
import static com.epam.reportportal.rules.exception.ErrorType.ACCESS_DENIED;
import static com.epam.reportportal.rules.exception.ErrorType.BAD_REQUEST_ERROR;
import static com.epam.ta.reportportal.commons.Predicates.equalTo;
import static com.epam.ta.reportportal.core.analyzer.auto.impl.AnalyzerUtils.getAnalyzerConfig;
import static com.epam.ta.reportportal.entity.enums.LogLevel.ERROR_INT;

import com.epam.reportportal.rules.exception.ErrorType;
import com.epam.reportportal.rules.exception.ReportPortalException;
import com.epam.ta.reportportal.commons.ReportPortalUser;
import com.epam.ta.reportportal.core.analyzer.auto.client.AnalyzerServiceClient;
import com.epam.ta.reportportal.core.analyzer.auto.client.model.SuggestInfo;
import com.epam.ta.reportportal.core.analyzer.auto.client.model.SuggestRq;
import com.epam.ta.reportportal.core.item.impl.LaunchAccessValidator;
import com.epam.ta.reportportal.core.item.validator.state.TestItemValidator;
import com.epam.ta.reportportal.core.launch.GetLaunchHandler;
import com.epam.ta.reportportal.core.launch.cluster.GetClusterHandler;
import com.epam.ta.reportportal.core.log.LogService;
import com.epam.ta.reportportal.core.project.GetProjectHandler;
import com.epam.ta.reportportal.dao.TestItemRepository;
import com.epam.ta.reportportal.entity.cluster.Cluster;
import com.epam.ta.reportportal.entity.item.TestItem;
import com.epam.ta.reportportal.entity.item.issue.IssueEntity;
import com.epam.ta.reportportal.entity.launch.Launch;
import com.epam.ta.reportportal.entity.project.Project;
import com.epam.ta.reportportal.entity.user.UserRole;
import com.epam.ta.reportportal.exception.AnalyzerBusyException;
import com.epam.ta.reportportal.ws.converter.converters.IssueConverter;
import com.epam.ta.reportportal.ws.converter.converters.LogConverter;
import com.epam.ta.reportportal.ws.converter.converters.TestItemConverter;
import com.epam.ta.reportportal.ws.reporting.OperationCompletionRS;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Requests suggestions from the analyzer.
 *
 * <p>The analyzer call is a blocking RPC that can take minutes. The service reads the data for the
 * request in one read-only transaction, calls the analyzer outside any transaction, and maps the
 * reply in a second read-only transaction, so no database connection is held while it waits.
 *
 * @author <a href="mailto:pavel_bortnik@epam.com">Pavel Bortnik</a>
 */
@Service
public class SuggestItemService {

  private static final int SUGGESTED_ITEMS_LOGS_LIMIT = 5;

  private final AnalyzerServiceClient analyzerServiceClient;

  private final GetProjectHandler getProjectHandler;
  private final GetLaunchHandler getLaunchHandler;
  private final GetClusterHandler getClusterHandler;

  private final LaunchAccessValidator launchAccessValidator;

  private final TestItemRepository testItemRepository;
  private final LogService logService;
  private final LogConverter logConverter;

  private final List<TestItemValidator> testItemValidators;

  private final TransactionTemplate readOnlyTransaction;

  /**
   * Limits concurrent suggest requests, {@code null} when there is no limit.
   */
  private final Semaphore suggestPermits;

  public SuggestItemService(AnalyzerServiceClient analyzerServiceClient,
      GetProjectHandler getProjectHandler, GetLaunchHandler getLaunchHandler,
      GetClusterHandler getClusterHandler, LaunchAccessValidator launchAccessValidator,
      TestItemRepository testItemRepository, LogService logService, LogConverter logConverter,
      List<TestItemValidator> testItemValidators, PlatformTransactionManager transactionManager,
      @Value("${rp.analyzer.suggest.max-concurrent:0}") int maxConcurrentSuggests) {
    this.analyzerServiceClient = analyzerServiceClient;
    this.getProjectHandler = getProjectHandler;
    this.getLaunchHandler = getLaunchHandler;
    this.getClusterHandler = getClusterHandler;
    this.launchAccessValidator = launchAccessValidator;
    this.testItemRepository = testItemRepository;
    this.logService = logService;
    this.logConverter = logConverter;
    this.testItemValidators = testItemValidators;
    this.readOnlyTransaction = new TransactionTemplate(transactionManager);
    this.readOnlyTransaction.setReadOnly(true);
    this.suggestPermits = maxConcurrentSuggests > 0 ? new Semaphore(maxConcurrentSuggests) : null;
  }

  public List<SuggestedItem> suggestItems(Long testItemId,
      ReportPortalUser.ProjectDetails projectDetails, ReportPortalUser user) {
    return withSuggestPermit(() -> {
      SuggestRq suggestRq = readOnlyTransaction.execute(status -> {
        TestItem testItem = testItemRepository.findById(testItemId)
            .orElseThrow(() -> new ReportPortalException(ErrorType.TEST_ITEM_NOT_FOUND, testItemId));

        validateTestItem(testItem);

        Launch launch = getLaunch(testItem.getLaunchId(), projectDetails, user);
        isItemUnderProject(projectDetails, user, launch);
        Project project = getProjectHandler.get(projectDetails);

        return prepareSuggestRq(testItem, launch, project);
      });
      return getSuggestedItems(suggestRq);
    });
  }

  private List<SuggestedItem> withSuggestPermit(Supplier<List<SuggestedItem>> suggest) {
    if (suggestPermits == null) {
      return suggest.get();
    }
    if (!suggestPermits.tryAcquire()) {
      throw new AnalyzerBusyException("Too many concurrent suggest requests, try again later.");
    }
    try {
      return suggest.get();
    } finally {
      suggestPermits.release();
    }
  }

  private void validateTestItem(TestItem testItem) {
    testItemValidators.forEach(v -> {
      if (!v.validate(testItem)) {
        throw new ReportPortalException(BAD_REQUEST_ERROR, v.provide(testItem));
      }
    });
  }

  private void isItemUnderProject(ReportPortalUser.ProjectDetails projectDetails,
      ReportPortalUser user, Launch launch) {
    if (user.getUserRole() != UserRole.ADMINISTRATOR) {
      expect(launch.getProjectId(), equalTo(projectDetails.getProjectId())).verify(ACCESS_DENIED,
          "Launch is not under the specified project."
      );
    }
  }

  public List<SuggestedItem> suggestClusterItems(Long clusterId,
      ReportPortalUser.ProjectDetails projectDetails, ReportPortalUser user) {
    return withSuggestPermit(() -> {
      final SuggestRq suggestRq = readOnlyTransaction.execute(status -> {
        final Cluster cluster = getClusterHandler.getById(clusterId);
        final Launch launch = getLaunch(cluster.getLaunchId(), projectDetails, user);
        final Project project = getProjectHandler.get(projectDetails);
        return prepareSuggestRq(cluster, launch, project);
      });
      return getSuggestedItems(suggestRq);
    });
  }

  private Launch getLaunch(Long launchId, ReportPortalUser.ProjectDetails projectDetails,
      ReportPortalUser user) {
    Launch launch = getLaunchHandler.get(launchId);
    launchAccessValidator.validate(launch, projectDetails, user);
    return launch;
  }

  private SuggestRq prepareSuggestRq(TestItem testItem, Launch launch, Project project) {
    SuggestRq suggestRq = prepareSuggestRq(launch, project);
    suggestRq.setTestItemId(testItem.getItemId());
    suggestRq.setUniqueId(testItem.getUniqueId());
    suggestRq.setTestCaseHash(testItem.getTestCaseHash());
    suggestRq.setLogs(AnalyzerUtils.fromLogs(
        logService.findAllUnderTestItemByLaunchIdAndTestItemIdsAndLogLevelGte(launch.getId(),
            Collections.singletonList(testItem.getItemId()),
            ERROR_INT
        )));
    return suggestRq;
  }

  private SuggestRq prepareSuggestRq(Cluster cluster, Launch launch, Project project) {
    SuggestRq suggestRq = prepareSuggestRq(launch, project);
    suggestRq.setClusterId(cluster.getIndexId());
    return suggestRq;
  }

  private SuggestRq prepareSuggestRq(Launch launch, Project project) {
    SuggestRq suggestRq = new SuggestRq();
    suggestRq.setLaunchId(launch.getId());
    suggestRq.setLaunchName(launch.getName());
    suggestRq.setProject(project.getId());
    suggestRq.setAnalyzerConfig(getAnalyzerConfig(project));
    suggestRq.setLaunchNumber(launch.getNumber());
    return suggestRq;
  }

  private List<SuggestedItem> getSuggestedItems(SuggestRq suggestRq) {
    // Blocking RPC to the analyzer, runs outside any transaction
    List<SuggestInfo> suggestInfos = analyzerServiceClient.searchSuggests(suggestRq);
    return readOnlyTransaction.execute(status -> suggestInfos
        .stream()
        .map(this::prepareSuggestedItem)
        .filter(Objects::nonNull)
        .collect(Collectors.toList()));
  }

  private SuggestedItem prepareSuggestedItem(SuggestInfo suggestInfo) {
    TestItem relevantTestItem = testItemRepository.findById(suggestInfo.getRelevantItem())
        .orElse(null);
    //TODO: EPMRPP-61038 temp fix for the case when item was removed from db but still exists in elastic
    if (relevantTestItem == null) {
      return null;
    }
    var issue = getSuggestedIssue(relevantTestItem);
    SuggestedItem suggestedItem = new SuggestedItem();
    roundSuggestInfoMatchScore(suggestInfo);
    suggestedItem.setSuggestRs(suggestInfo);
    suggestedItem.setTestItemResource(TestItemConverter.TO_RESOURCE.apply(relevantTestItem));
    issue.ifPresent(issueEntity -> suggestedItem.getTestItemResource()
        .setIssue(IssueConverter.TO_MODEL.apply(issueEntity)));
    suggestedItem.setLogs(logService.findLatestUnderTestItemByLaunchIdAndTestItemIdsAndLogLevelGte(
        relevantTestItem.getLaunchId(),
        relevantTestItem.getItemId(),
        ERROR_INT,
        SUGGESTED_ITEMS_LOGS_LIMIT
    ).stream().map(logConverter::toResource).collect(Collectors.toSet()));
    return suggestedItem;
  }

  private Optional<IssueEntity> getSuggestedIssue(TestItem relevantTestItem) {
    if (relevantTestItem.getRetryOf() != null) {
      Optional<TestItem> latestRetry = testItemRepository.findById(relevantTestItem.getRetryOf());
      if (latestRetry.isPresent()) {
        return Optional.ofNullable(latestRetry.get().getItemResults().getIssue());
      }
    }
    return Optional.empty();
  }

  private void roundSuggestInfoMatchScore(SuggestInfo info) {
    float roundedMatchScore = Math.round(info.getMatchScore());
    info.setMatchScore(roundedMatchScore);
  }

  public OperationCompletionRS handleSuggestChoice(List<SuggestInfo> suggestInfos) {
    analyzerServiceClient.handleSuggestChoice(suggestInfos);
    return new OperationCompletionRS("User choice of suggested item was sent for handling to ML");
  }
}
