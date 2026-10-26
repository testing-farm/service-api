package com.epam.reportportal.base.core.analyzer.auto.impl;

import static com.epam.reportportal.base.ReportPortalUserUtil.getRpUser;
import static com.epam.reportportal.base.infrastructure.persistence.entity.enums.LogLevel.ERROR_INT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.reportportal.base.core.analyzer.auto.client.AnalyzerServiceClient;
import com.epam.reportportal.base.core.analyzer.auto.client.model.SuggestInfo;
import com.epam.reportportal.base.core.analyzer.auto.client.model.SuggestRq;
import com.epam.reportportal.base.core.item.impl.LaunchAccessValidator;
import com.epam.reportportal.base.core.item.validator.state.TestItemValidator;
import com.epam.reportportal.base.core.launch.GetLaunchHandler;
import com.epam.reportportal.base.core.launch.cluster.GetClusterHandler;
import com.epam.reportportal.base.core.log.LogService;
import com.epam.reportportal.base.core.project.GetProjectHandler;
import com.epam.reportportal.base.exception.AnalyzerBusyException;
import com.epam.reportportal.base.infrastructure.persistence.commons.ReportPortalUser;
import com.epam.reportportal.base.infrastructure.persistence.dao.TestItemRepository;
import com.epam.reportportal.base.infrastructure.persistence.entity.cluster.Cluster;
import com.epam.reportportal.base.infrastructure.persistence.entity.item.TestItem;
import com.epam.reportportal.base.infrastructure.persistence.entity.item.TestItemResults;
import com.epam.reportportal.base.infrastructure.persistence.entity.launch.Launch;
import com.epam.reportportal.base.infrastructure.persistence.entity.log.LogFull;
import com.epam.reportportal.base.infrastructure.persistence.entity.organization.MembershipDetails;
import com.epam.reportportal.base.infrastructure.persistence.entity.organization.OrganizationRole;
import com.epam.reportportal.base.infrastructure.persistence.entity.project.Project;
import com.epam.reportportal.base.infrastructure.persistence.entity.project.ProjectRole;
import com.epam.reportportal.base.infrastructure.persistence.entity.user.UserRole;
import com.epam.reportportal.base.infrastructure.rules.exception.ReportPortalException;
import com.epam.reportportal.base.reporting.OperationCompletionRS;
import com.epam.reportportal.base.ws.converter.converters.LogConverter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class SuggestItemServiceTest {

  private final AnalyzerServiceClient analyzerServiceClient = mock(AnalyzerServiceClient.class);

  private final GetProjectHandler getProjectHandler = mock(GetProjectHandler.class);
  private final GetLaunchHandler getLaunchHandler = mock(GetLaunchHandler.class);
  private final GetClusterHandler getClusterHandler = mock(GetClusterHandler.class);

  private final LaunchAccessValidator launchAccessValidator = mock(LaunchAccessValidator.class);

  private final TestItemRepository testItemRepository = mock(TestItemRepository.class);
  private final LogService logService = mock(LogService.class);

  private final TestItemValidator testItemValidator = mock(TestItemValidator.class);
  private final LogConverter logConverter = mock(LogConverter.class);
  private final List<TestItemValidator> validators = List.of(testItemValidator);

  private final TrackingTransactionManager transactionManager = new TrackingTransactionManager();

  private final SuggestItemService suggestItemService = createService(0);

  private SuggestItemService createService(int maxConcurrentSuggests) {
    return new SuggestItemService(
        analyzerServiceClient,
        getProjectHandler,
        getLaunchHandler,
        getClusterHandler,
        launchAccessValidator,
        testItemRepository,
        logService,
        logConverter,
        validators,
        transactionManager,
        maxConcurrentSuggests
    );
  }

  /**
   * Records whether a transaction is active and how many were started.
   */
  private static class TrackingTransactionManager implements PlatformTransactionManager {

    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicInteger started = new AtomicInteger();

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      Assertions.assertTrue(definition.isReadOnly(), "Suggest transactions should be read-only");
      active.set(true);
      started.incrementAndGet();
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      active.set(false);
    }

    @Override
    public void rollback(TransactionStatus status) {
      active.set(false);
    }
  }

  @Test
  void suggestItems() {
    final ReportPortalUser rpUser = getRpUser("owner", UserRole.USER, OrganizationRole.MEMBER, ProjectRole.VIEWER, 1L);
    final Project project = new Project();
    project.setId(1L);
    project.setKey("default");

    TestItem testItem = new TestItem();
    testItem.setItemId(1L);
    testItem.setLaunchId(1L);

    TestItem relevantItem = getRelevantItem();

    Launch launch = new Launch();
    launch.setId(1L);
    launch.setProjectId(1L);

    final LogFull logFull = new LogFull();

    SuggestInfo suggestInfo = new SuggestInfo();
    suggestInfo.setRelevantItem(2L);

    when(testItemRepository.findById(1L)).thenReturn(Optional.of(testItem));
    when(testItemValidator.validate(any(TestItem.class))).thenReturn(true);
    when(testItemRepository.findById(2L)).thenReturn(Optional.of(relevantItem));
    when(getLaunchHandler.get(testItem.getLaunchId())).thenReturn(launch);
    when(getProjectHandler.get(any(MembershipDetails.class))).thenReturn(project);
    when(logService.findAllUnderTestItemByLaunchIdAndTestItemIdsAndLogLevelGte(launch.getId(),
        Collections.singletonList(testItem.getItemId()),
        ERROR_INT
    )).thenReturn(Collections.singletonList(logFull));

    when(analyzerServiceClient.searchSuggests(any(SuggestRq.class))).thenReturn(
        Collections.singletonList(suggestInfo));

    final List<SuggestedItem> suggestedItems = suggestItemService.suggestItems(1L,
        MembershipDetails.builder()
            .withProjectId(1L)
            .withProjectRole(ProjectRole.VIEWER)
            .build(),
        rpUser
    );

    Assertions.assertEquals(1, suggestedItems.size());

  }

  @Test
  void suggestRemovedItems() {
    final ReportPortalUser rpUser = getRpUser("owner", UserRole.USER, OrganizationRole.MEMBER, ProjectRole.VIEWER, 1L);
    final Project project = new Project();
    project.setId(1L);
    project.setKey("default");

    TestItem testItem = new TestItem();
    testItem.setItemId(1L);
    testItem.setLaunchId(1L);

    Launch launch = new Launch();
    launch.setProjectId(1L);
    launch.setId(1L);

    SuggestInfo suggestInfo = new SuggestInfo();
    suggestInfo.setRelevantItem(2L);

    when(testItemRepository.findById(1L)).thenReturn(Optional.of(testItem));
    when(testItemValidator.validate(any(TestItem.class))).thenReturn(true);
    when(getLaunchHandler.get(testItem.getLaunchId())).thenReturn(launch);
    when(getProjectHandler.get(any(MembershipDetails.class))).thenReturn(project);
    when(testItemRepository.findById(2L)).thenReturn(Optional.empty());

    when(analyzerServiceClient.searchSuggests(any(SuggestRq.class))).thenReturn(
        Collections.singletonList(suggestInfo));

    final List<SuggestedItem> suggestedItems = suggestItemService.suggestItems(1L,
        MembershipDetails.builder().withProjectId(1L)
            .withProjectRole(ProjectRole.VIEWER).build(),
        rpUser
    );

    Assertions.assertTrue(suggestedItems.isEmpty());

  }

  @Test
  void showThrowExceptionWhenNotValid() {
    final ReportPortalUser rpUser = getRpUser("owner", UserRole.USER, OrganizationRole.MEMBER, ProjectRole.VIEWER, 1L);

    TestItem testItem = new TestItem();
    testItem.setItemId(1L);

    when(testItemRepository.findById(1L)).thenReturn(Optional.of(testItem));
    when(testItemValidator.validate(testItem)).thenReturn(false);
    when(testItemValidator.provide(testItem)).thenReturn("Test item = 1 is a nested step");

    final ReportPortalException exception = Assertions.assertThrows(ReportPortalException.class,
        () -> suggestItemService.suggestItems(1L,
            MembershipDetails.builder().withProjectId(1L)
                .withProjectRole(ProjectRole.VIEWER).build(),
            rpUser
        )
    );

    Assertions.assertEquals(
        "Error in handled Request. Please, check specified parameters: 'Test item = 1 is a nested step'",
        exception.getMessage());
  }

  @Test
  void showThrowExceptionWhenNotFound() {
    final ReportPortalUser rpUser = getRpUser("owner", UserRole.USER, OrganizationRole.MEMBER, ProjectRole.VIEWER, 1L);

    when(testItemRepository.findById(1L)).thenReturn(Optional.empty());

    final ReportPortalException exception = Assertions.assertThrows(ReportPortalException.class,
        () -> suggestItemService.suggestItems(1L,
            MembershipDetails.builder().withProjectId(1L)
                .withProjectRole(ProjectRole.VIEWER).build(),
            rpUser
        )
    );

    Assertions.assertEquals("Test Item '1' not found. Did you use correct Test Item ID?",
        exception.getMessage());
  }

  @Test
  void suggestClusterItems() {
    final ReportPortalUser rpUser = getRpUser("owner", UserRole.USER, OrganizationRole.MEMBER, ProjectRole.VIEWER, 1L);
    final Project project = new Project();
    project.setId(1L);
    project.setKey("default");

    final Cluster cluster = new Cluster();
    cluster.setId(1L);
    cluster.setProjectId(1L);
    cluster.setLaunchId(1L);

    TestItem relevantItem = getRelevantItem();

    Launch launch = new Launch();
    launch.setProjectId(1L);
    launch.setId(1L);

    final LogFull logFull = new LogFull();

    SuggestInfo suggestInfo = new SuggestInfo();
    suggestInfo.setRelevantItem(2L);

    when(getClusterHandler.getById(1L)).thenReturn(cluster);
    when(testItemRepository.findById(2L)).thenReturn(Optional.of(relevantItem));
    when(getLaunchHandler.get(cluster.getLaunchId())).thenReturn(launch);
    when(getProjectHandler.get(any(MembershipDetails.class))).thenReturn(project);
    when(logService.findAllUnderTestItemByLaunchIdAndTestItemIdsAndLogLevelGte(launch.getId(),
        Collections.singletonList(relevantItem.getItemId()),
        ERROR_INT
    )).thenReturn(Collections.singletonList(logFull));

    when(analyzerServiceClient.searchSuggests(any(SuggestRq.class))).thenReturn(
        Collections.singletonList(suggestInfo));

    final List<SuggestedItem> suggestedItems =
        suggestItemService.suggestClusterItems(1L, MembershipDetails.builder()
                .withProjectId(1L)
                .withProjectRole(ProjectRole.VIEWER).build(),
            rpUser
        );

    Assertions.assertEquals(1, suggestedItems.size());

  }

  @Test
  void handleSuggestChoice() {
    final OperationCompletionRS operationCompletionRS = suggestItemService.handleSuggestChoice(
        new ArrayList<>());
    verify(analyzerServiceClient, times(1)).handleSuggestChoice(anyList());
    Assertions.assertEquals("User choice of suggested item was sent for handling to ML",
        operationCompletionRS.getResultMessage());
  }

  private TestItem getRelevantItem() {

    TestItem relevantItem = new TestItem();
    relevantItem.setItemId(2L);

    TestItemResults relevantItemRes = new TestItemResults();
    relevantItemRes.setEndTime(Instant.now());

    relevantItem.setItemResults(relevantItemRes);

    return relevantItem;
  }

  private void mockSuggestData(Long launchId) {
    final Project project = new Project();
    project.setId(1L);
    project.setKey("default");
    TestItem testItem = new TestItem();
    testItem.setItemId(1L);
    testItem.setLaunchId(launchId);

    Launch launch = new Launch();
    launch.setId(launchId);
    launch.setProjectId(1L);

    SuggestInfo suggestInfo = new SuggestInfo();
    suggestInfo.setRelevantItem(2L);

    when(testItemRepository.findById(1L)).thenReturn(Optional.of(testItem));
    when(testItemValidator.validate(any(TestItem.class))).thenReturn(true);
    when(testItemRepository.findById(2L)).thenReturn(Optional.of(getRelevantItem()));
    when(getLaunchHandler.get(launchId)).thenReturn(launch);
    when(getProjectHandler.get(any(MembershipDetails.class))).thenReturn(project);
    when(analyzerServiceClient.searchSuggests(any(SuggestRq.class))).thenReturn(
        Collections.singletonList(suggestInfo));
  }

  private List<SuggestedItem> suggest(SuggestItemService service) {
    return service.suggestItems(1L,
        MembershipDetails.builder()
            .withProjectId(1L)
            .withProjectRole(ProjectRole.VIEWER)
            .build(),
        getRpUser("owner", UserRole.USER, OrganizationRole.MEMBER, ProjectRole.VIEWER, 1L)
    );
  }

  @Test
  void suggestCallsAnalyzerOutsideTransaction() {
    mockSuggestData(1L);
    AtomicBoolean activeDuringRpc = new AtomicBoolean(true);
    doAnswer(invocation -> {
      activeDuringRpc.set(transactionManager.active.get());
      SuggestInfo suggestInfo = new SuggestInfo();
      suggestInfo.setRelevantItem(2L);
      return Collections.singletonList(suggestInfo);
    }).when(analyzerServiceClient).searchSuggests(any(SuggestRq.class));

    final List<SuggestedItem> suggestedItems = suggest(suggestItemService);

    Assertions.assertEquals(1, suggestedItems.size());
    Assertions.assertFalse(activeDuringRpc.get(), "The analyzer RPC should run without a transaction");
    Assertions.assertEquals(2, transactionManager.started.get());
    Assertions.assertFalse(transactionManager.active.get());
  }

  @Test
  void suggestRejectsRequestsPastLimit() throws Exception {
    mockSuggestData(1L);
    final SuggestItemService limitedService = createService(1);
    CountDownLatch rpcStarted = new CountDownLatch(1);
    CountDownLatch releaseRpc = new CountDownLatch(1);
    doAnswer(invocation -> {
      rpcStarted.countDown();
      Assertions.assertTrue(releaseRpc.await(10, TimeUnit.SECONDS));
      return Collections.emptyList();
    }).when(analyzerServiceClient).searchSuggests(any(SuggestRq.class));

    CompletableFuture<List<SuggestedItem>> first = CompletableFuture.supplyAsync(
        () -> suggest(limitedService));
    Assertions.assertTrue(rpcStarted.await(10, TimeUnit.SECONDS));

    Assertions.assertThrows(AnalyzerBusyException.class, () -> suggest(limitedService));

    releaseRpc.countDown();
    Assertions.assertTrue(first.get(10, TimeUnit.SECONDS).isEmpty());

    // The permit is released after the first request finishes
    Assertions.assertTrue(suggest(limitedService).isEmpty());
  }

  @Test
  void suggestReleasesPermitOnError() {
    final SuggestItemService limitedService = createService(1);
    when(testItemRepository.findById(1L)).thenReturn(Optional.empty());

    Assertions.assertThrows(ReportPortalException.class, () -> suggest(limitedService));
    Assertions.assertThrows(ReportPortalException.class, () -> suggest(limitedService));
    Assertions.assertFalse(transactionManager.active.get());
  }
}
