package com.epam.reportportal.testng;

import com.epam.reportportal.listeners.ListenerParameters;
import com.epam.reportportal.restendpoint.http.MultiPartRequest;
import com.epam.reportportal.service.ReportPortal;
import com.epam.reportportal.service.ReportPortalClient;
import com.epam.reportportal.testng.integration.CallbackReportingListener;
import com.epam.reportportal.testng.integration.feature.callback.CallbackReportingTest;
import com.epam.reportportal.testng.integration.util.TestUtils;
import com.epam.reportportal.utils.properties.PropertiesLoader;
import com.epam.ta.reportportal.ws.model.*;
import com.epam.ta.reportportal.ws.model.item.ItemCreatedRS;
import com.epam.ta.reportportal.ws.model.launch.StartLaunchRS;
import com.epam.ta.reportportal.ws.model.log.SaveLogRQ;
import io.reactivex.Maybe;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static java.util.stream.Collectors.groupingBy;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

/**
 * @author <a href="mailto:ivan_budayeu@epam.com">Ivan Budayeu</a>
 */
public class CallbackReportingIntegrationTest {

	private final String suitedUuid = UUID.randomUUID().toString();
	private final String testClassUuid = UUID.randomUUID().toString();
	private final String testMethodUuid = UUID.randomUUID().toString();

	@Before
	public void initMocks() {
		ReportPortalClient reportPortalClient = mock(ReportPortalClient.class);

		when(reportPortalClient.startLaunch(any())).thenReturn(TestUtils.createMaybe(new StartLaunchRS("launchUuid", 1L)));

		Maybe<ItemCreatedRS> suiteMaybe = TestUtils.createMaybe(new ItemCreatedRS(suitedUuid, suitedUuid));
		when(reportPortalClient.startTestItem(any())).thenReturn(suiteMaybe);

		Maybe<ItemCreatedRS> testClassMaybe = TestUtils.createMaybe(new ItemCreatedRS(testClassUuid, testClassUuid));
		when(reportPortalClient.startTestItem(eq(suiteMaybe.blockingGet().getId()), any())).thenReturn(testClassMaybe);

		Maybe<ItemCreatedRS> testMethodMaybe = TestUtils.createMaybe(new ItemCreatedRS(testMethodUuid, testMethodUuid));
		when(reportPortalClient.startTestItem(eq(testClassMaybe.blockingGet().getId()), any())).thenReturn(testMethodMaybe);

		Maybe<OperationCompletionRS> finishResponse = TestUtils.createMaybe(new OperationCompletionRS("finished"));
		when(reportPortalClient.finishTestItem(eq(testMethodUuid), any())).thenReturn(finishResponse);

		when(reportPortalClient.log(any(MultiPartRequest.class))).thenReturn(TestUtils.createMaybe(new BatchSaveOperatingRS()));
		when(reportPortalClient.log(any(SaveLogRQ.class))).thenReturn(TestUtils.createMaybe(new EntryCreatedAsyncRS("logId")));

		final ReportPortal reportPortal = ReportPortal.create(reportPortalClient, new ListenerParameters(PropertiesLoader.load()));
		CallbackReportingListener.initReportPortal(reportPortal);
	}

	@Test
	public void callbackReportingTest() {

		ReportPortalClient client = CallbackReportingListener.getReportPortal().getClient();

		try {
			TestUtils.runTests(Collections.singletonList(CallbackReportingListener.class), CallbackReportingTest.class);
		} catch (Exception ex) {
			//do nothing
			ex.printStackTrace();
		}

		verify(client, times(1)).startTestItem(any());  // Start parent suite

		ArgumentCaptor<FinishTestItemRQ> captor = ArgumentCaptor.forClass(FinishTestItemRQ.class);
		verify(client, times(6)).finishTestItem(eq(testMethodUuid), captor.capture()); // Start test class and test method

		ArgumentCaptor<SaveLogRQ> saveLogRQArgumentCaptor = ArgumentCaptor.forClass(SaveLogRQ.class);
		verify(client, times(1)).log(saveLogRQArgumentCaptor.capture());

		Map<String, List<FinishTestItemRQ>> finishMapping = captor.getAllValues()
				.stream()
				.filter(it -> Objects.nonNull(it.getDescription()))
				.collect(groupingBy(FinishExecutionRQ::getDescription));

		FinishTestItemRQ firstTestCallbackFinish = finishMapping.get("firstTest").get(0);
		FinishTestItemRQ secondTestCallbackFinish = finishMapping.get("secondTest").get(0);

		assertEquals("PASSED", firstTestCallbackFinish.getStatus());
		assertEquals("FAILED", secondTestCallbackFinish.getStatus());

		SaveLogRQ logRequest = saveLogRQArgumentCaptor.getValue();

		assertEquals("Error message", logRequest.getMessage());
		assertEquals("ERROR", logRequest.getLevel());

	}
}