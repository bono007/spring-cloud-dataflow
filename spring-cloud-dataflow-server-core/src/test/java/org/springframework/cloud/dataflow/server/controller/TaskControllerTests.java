/*
 * Copyright 2015-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.dataflow.server.controller;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.hibernate.AssertionFailure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.dataflow.core.ApplicationType;
import org.springframework.cloud.dataflow.core.Launcher;
import org.springframework.cloud.dataflow.core.TaskDefinition;
import org.springframework.cloud.dataflow.core.TaskManifest;
import org.springframework.cloud.dataflow.registry.service.AppRegistryService;
import org.springframework.cloud.dataflow.server.config.apps.CommonApplicationProperties;
import org.springframework.cloud.dataflow.server.configuration.TestDependencies;
import org.springframework.cloud.dataflow.server.controller.assembler.TaskDefinitionAssemblerProvider;
import org.springframework.cloud.dataflow.server.job.LauncherRepository;
import org.springframework.cloud.dataflow.server.repository.DataflowTaskExecutionMetadataDao;
import org.springframework.cloud.dataflow.server.repository.TaskDefinitionRepository;
import org.springframework.cloud.dataflow.server.service.TaskDeleteService;
import org.springframework.cloud.dataflow.server.service.TaskExecutionCreationService;
import org.springframework.cloud.dataflow.server.service.TaskSaveService;
import org.springframework.cloud.dataflow.server.task.DataflowTaskExplorer;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.task.repository.TaskExecution;
import org.springframework.cloud.task.repository.dao.TaskExecutionDao;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author Michael Minella
 * @author Mark Fisher
 * @author Glenn Renfro
 * @author Gunnar Hillert
 * @author Ilayaperumal Gopinathan
 * @author Christian Tzolov
 * @author Corneil du Plessis
 */
@SpringBootTest(classes = TestDependencies.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@AutoConfigureTestDatabase(replace = Replace.ANY)
class TaskControllerTests {

	private final static String TASK_NAME_REQUEST_PARAMETER = "taskName";

	@Autowired
	TaskDefinitionAssemblerProvider taskDefinitionAssemblerProvider;

	@Autowired
	private TaskDefinitionRepository repository;

	@Autowired
	private AppRegistryService registry;

	private MockMvc mockMvc;

	@Autowired
	private WebApplicationContext wac;

	@Autowired
	private TaskLauncher taskLauncher;

	@Autowired
	private LauncherRepository launcherRepository;

	@Autowired
	private DataflowTaskExplorer taskExplorer;

	@Autowired
	private TaskSaveService taskSaveService;

	@Autowired
	private TaskDeleteService taskDeleteService;

	@Autowired
	private DataflowTaskExecutionMetadataDao dataflowTaskExecutionMetadataDao;

	@Autowired
	private TaskExecutionDao taskExecutionDao;

	@Autowired
	private TaskExecutionCreationService taskExecutionCreationService;

	@Autowired
	private CommonApplicationProperties appsProperties;

	private boolean initialized = false;

	private static List<String> SAMPLE_ARGUMENT_LIST;

	private static List<String> SAMPLE_CLEANSED_ARGUMENT_LIST;

	@BeforeEach
	void setupMockMVC() {
		this.mockMvc = MockMvcBuilders.webAppContextSetup(wac)
				.defaultRequest(get("/").accept(MediaType.APPLICATION_JSON)).build();

		launcherRepository.save(new Launcher("default", "local", taskLauncher));
		when(taskLauncher.launch(any(AppDeploymentRequest.class))).thenReturn("testID");

		if (!initialized) {
			SAMPLE_ARGUMENT_LIST = new LinkedList<>();
			SAMPLE_ARGUMENT_LIST.add("--password=password");
			SAMPLE_ARGUMENT_LIST.add("--regular=value");

			SAMPLE_CLEANSED_ARGUMENT_LIST = new LinkedList<>();
			SAMPLE_CLEANSED_ARGUMENT_LIST.add("--password=******");
			SAMPLE_CLEANSED_ARGUMENT_LIST.add("--regular=value");

			initialized = true;
		}

		Map<String, String> deploymentProperties = new HashMap<>();
		deploymentProperties.put("app.test.key1", "value1");
		TaskManifest taskManifest = new TaskManifest();
		AppDeploymentRequest request = new AppDeploymentRequest(new AppDefinition("test", Collections.emptyMap()),
				new FileSystemResource(""),
				deploymentProperties,
				null);
		taskManifest.setTaskDeploymentRequest(request);
		taskManifest.setPlatformName("test");

		TaskExecution taskExecutionRunning = this.taskExecutionCreationService.createTaskExecution("myTask", null);
		assertThat(taskExecutionRunning.getExecutionId()).isGreaterThan(0L);
		taskExecutionRunning.setStartTime(LocalDateTime.now());
		taskExecutionRunning.setArguments(SAMPLE_ARGUMENT_LIST);

		taskExecutionDao.startTaskExecution(taskExecutionRunning.getExecutionId(),
				taskExecutionRunning.getTaskName(),
				LocalDateTime.now(),
				SAMPLE_ARGUMENT_LIST,
				Long.toString(taskExecutionRunning.getExecutionId()));
		taskExecutionRunning = taskExecutionDao.getTaskExecution(taskExecutionRunning.getExecutionId());
		dataflowTaskExecutionMetadataDao.save(taskExecutionRunning, taskManifest);

		TaskExecution taskExecutionComplete = this.taskExecutionCreationService.createTaskExecution("myTask2", null);
		assertThat(taskExecutionComplete.getExecutionId()).isGreaterThan(0L);
		taskExecutionDao.startTaskExecution(taskExecutionComplete.getExecutionId(),
				taskExecutionComplete.getTaskName(),
				LocalDateTime.now(),
				SAMPLE_ARGUMENT_LIST,
				Long.toString(taskExecutionComplete.getExecutionId()));
		taskExecutionDao.completeTaskExecution(taskExecutionComplete.getExecutionId(), 0, LocalDateTime.now(), null);
		taskExecutionComplete = taskExecutionDao.getTaskExecution(taskExecutionComplete.getExecutionId());
		dataflowTaskExecutionMetadataDao.save(taskExecutionComplete, taskManifest);
	}

	@Test
	void taskDefinitionControllerConstructorMissingRepository() {
		assertThatIllegalArgumentException().isThrownBy(() ->
				new TaskDefinitionController(this.taskExplorer, null, taskSaveService, taskDeleteService, taskDefinitionAssemblerProvider));
	}

	@Test
	void taskDefinitionControllerConstructorMissingTaskExplorer() {
		assertThatIllegalArgumentException().isThrownBy(() ->
				new TaskDefinitionController(null, this.repository, taskSaveService, taskDeleteService, taskDefinitionAssemblerProvider));
	}

	@Test
	void taskLaunchWithNullIDReturned() throws Exception {
		when(taskLauncher.launch(any(AppDeploymentRequest.class))).thenReturn(null);
		repository.save(new TaskDefinition("myTask", "foo"));
		this.registry.save("foo", ApplicationType.task,
				"1.0.0", new URI("maven://org.springframework.cloud:foo:1"), null);

		mockMvc.perform(post("/tasks/executions").param("name", "myTask").accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isInternalServerError());
	}

	@Test
	void saveErrorNotInRegistry() throws Exception {
		assertThat(repository.count()).isZero();

		mockMvc.perform(post("/tasks/definitions").param("name", "myTask").param("definition", "task")
				.accept(MediaType.APPLICATION_JSON)).andDo(print()).andExpect(status().isNotFound());

		assertThat(repository.count()).isZero();
	}

	@Test
	void save() throws Exception {
		assertThat(repository.count()).isZero();
		this.registry.save("task", ApplicationType.task, "1.0.0", new URI("https://fake.example.com/"), null);
		mockMvc.perform(post("/tasks/definitions").param("name", "myTask").param("definition", "task")
				.accept(MediaType.APPLICATION_JSON)).andDo(print()).andExpect(status().isOk());

		assertThat(repository.count()).isEqualTo(1);

		Optional<TaskDefinition> myTaskOpt = repository.findById("myTask");
		assertThat(myTaskOpt).isPresent();
		TaskDefinition myTask = myTaskOpt.get();

		assertThat(myTask.getProperties()).hasSize(1);
		assertThat(myTask.getProperties()).containsEntry("spring.cloud.task.name", "myTask");
		assertThat(myTask.getDslText()).isEqualTo("task");
		assertThat(myTask.getName()).isEqualTo("myTask");
	}

	@Test
	void saveDuplicate() throws Exception {
		this.registry.save("task", ApplicationType.task, "1.0.0", new URI("https://fake.example.com/"), null);
		repository.save(new TaskDefinition("myTask", "task"));
		mockMvc.perform(post("/tasks/definitions").param("name", "myTask").param("definition", "task")
				.accept(MediaType.APPLICATION_JSON)).andExpect(status().isConflict());
		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	void saveWithParameters() throws Exception {

		this.registry.save("task", ApplicationType.task, "1.0.0", new URI("https://fake.example.com/"), null);
		mockMvc.perform(post("/tasks/definitions").param("name", "myTask")
						.param("definition", "task --foo=bar --bar=baz").accept(MediaType.APPLICATION_JSON)).andDo(print())
				.andExpect(status().isOk());

		assertThat(repository.count()).isEqualTo(1);

		TaskDefinition myTask = repository.findById("myTask").orElseThrow(() -> new AssertionFailure("Expected myTask"));

		assertThat(myTask.getProperties()).containsEntry("foo", "bar");
		assertThat(myTask.getProperties()).containsEntry("bar", "baz");
		assertThat(myTask.getDslText()).isEqualTo("task --foo=bar --bar=baz");
		assertThat(myTask.getRegisteredAppName()).isEqualTo("task");
		assertThat(myTask.getName()).isEqualTo("myTask");

	}

	@Test
	void taskDefinitionWithLastExecutionDetail() throws Exception {
		this.registry.save("task", ApplicationType.task, "1.0.0", new URI("https://fake.example.com/"), null);
		mockMvc.perform(post("/tasks/definitions").param("name", "myTask")
						.param("definition", "task --foo=bar --bar=baz").accept(MediaType.APPLICATION_JSON)).andDo(print())
				.andExpect(status().isOk());
		mockMvc.perform(get("/tasks/definitions/myTask")
						.accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
				.andExpect(jsonPath("$.lastTaskExecution.deploymentProperties", is(nullValue())));
		mockMvc.perform(get("/tasks/definitions/myTask?manifest=true")
						.accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
				.andExpect(jsonPath("$.lastTaskExecution.deploymentProperties", hasEntry("app.test.key1", "value1")));
		mockMvc.perform(get("/tasks/definitions")
						.accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[0].lastTaskExecution.deploymentProperties", is(nullValue())));
		mockMvc.perform(get("/tasks/definitions?manifest=true")
						.accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[0].lastTaskExecution.deploymentProperties", hasEntry("app.test.key1", "value1")));
	}

	@Test
	void saveCompositeTaskWithParameters() throws Exception {

		registry.save("task", ApplicationType.task, "1.0.0", new URI("https://fake.example.com/"), null);
		mockMvc.perform(post("/tasks/definitions").param("name", "myTask")
						.param("definition", "t1: task --foo='bar rab' && t2: task --foo='one two'")
						.accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk());

		assertThat(repository.count()).isEqualTo(3);

		Optional<TaskDefinition> myTask1Opt = repository.findById("myTask-t1");
		assertThat(myTask1Opt).isPresent();
		TaskDefinition myTask1 = myTask1Opt.get();
		assertThat(myTask1.getProperties()).containsEntry("foo", "bar rab");
		assertThat(myTask1.getDslText()).isEqualTo("t1:task --foo='bar rab'");
		assertThat(myTask1.getRegisteredAppName()).isEqualTo("task");
		assertThat(myTask1.getName()).isEqualTo("myTask-t1");

		Optional<TaskDefinition> myTask2Opt = repository.findById("myTask-t2");
		assertThat(myTask2Opt).isPresent();
		TaskDefinition myTask2 = myTask2Opt.get();
		assertThat(myTask2.getProperties()).containsEntry("foo", "one two");
		assertThat(myTask2.getDslText()).isEqualTo("t2:task --foo='one two'");
		assertThat(myTask2.getRegisteredAppName()).isEqualTo("task");
		assertThat(myTask2.getName()).isEqualTo("myTask-t2");
	}

	@Test
	void findTaskNameContainsSubstring() throws Exception {
		repository.save(new TaskDefinition("foo", "task"));
		repository.save(new TaskDefinition("foz", "task"));
		repository.save(new TaskDefinition("ooz", "task"));

		mockMvc.perform(get("/tasks/definitions").param(TASK_NAME_REQUEST_PARAMETER, "f")
						.accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList.*", hasSize(2)))

				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[0].name", is("foo")))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[1].name", is("foz")));

		mockMvc.perform(get("/tasks/definitions").param(TASK_NAME_REQUEST_PARAMETER, "oz")
						.accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList.*", hasSize(2)))

				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[0].name", is("foz")))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[1].name", is("ooz")));

		mockMvc.perform(get("/tasks/definitions").param(TASK_NAME_REQUEST_PARAMETER, "o")
						.accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList.*", hasSize(3)))

				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[0].name", is("foo")))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[1].name", is("foz")))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[2].name", is("ooz")));
	}

	@Test
	void findTaskDescriptionAndDslContainsSubstring() throws Exception {
		repository.save(new TaskDefinition("foo", "fooDsl", "fooTask"));
		repository.save(new TaskDefinition("foz", "fozDsl", "fozTask"));

		mockMvc.perform(get("/tasks/definitions").param("description", "fooTask")
						.accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList.*", hasSize(1)))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[0].description", is("fooTask")));

		mockMvc.perform(get("/tasks/definitions").param("dslText", "fozDsl")
						.accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList.*", hasSize(1)))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[0].dslText", is("fozDsl")));
	}

	@Test
	void findDslTextContainsSubstring() throws Exception {
		repository.save(new TaskDefinition("foo", "task-foo"));
		repository.save(new TaskDefinition("foz", "task-foz"));
		repository.save(new TaskDefinition("ooz", "task-ooz"));

		mockMvc.perform(get("/tasks/definitions").param("dslText", "fo")
						.accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList.*", hasSize(2)))

				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[0].dslText", is("task-foo")))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[1].dslText", is("task-foz")));

		mockMvc.perform(get("/tasks/definitions").param("dslText", "oz")
						.accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList.*", hasSize(2)))

				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[0].dslText", is("task-foz")))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[1].dslText", is("task-ooz")));

		mockMvc.perform(get("/tasks/definitions").param("dslText", "o")
						.accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList.*", hasSize(3)))

				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[0].dslText", is("task-foo")))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[1].dslText", is("task-foz")))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[2].dslText", is("task-ooz")));
	}

	@Test
	void findByDslTextAndNameBadRequest() throws Exception {
		mockMvc.perform(get("/tasks/definitions").param("dslText", "fo").param(TASK_NAME_REQUEST_PARAMETER, "f")
				.accept(MediaType.APPLICATION_JSON)).andExpect(status().isBadRequest());
	}

	@Test
	void destroyTask() throws Exception {
		repository.save(new TaskDefinition("myTask", "task"));

		mockMvc.perform(delete("/tasks/definitions/myTask").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk());

		assertThat(repository.count()).isZero();
	}

	@Test
	void destroyTaskNotFound() throws Exception {
		mockMvc.perform(delete("/tasks/definitions/myTask").accept(MediaType.APPLICATION_JSON)).andDo(print())
				.andExpect(status().isNotFound());
		assertThat(repository.count()).isZero();
	}

	@Test
	void destroyAllTask() throws Exception {
		repository.save(new TaskDefinition("myTask1", "task"));
		repository.save(new TaskDefinition("myTask2", "task && task2"));
		repository.save(new TaskDefinition("myTask3", "task"));

		assertThat(repository.count()).isEqualTo(3);

		mockMvc.perform(get("/tasks/definitions").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList", hasSize(3)));

		mockMvc.perform(delete("/tasks/definitions").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk());

		assertThat(repository.count()).isZero();
	}

	@Test
	void ctrDeleteOutOfSequence() throws Exception {
		repository.save(new TaskDefinition("myTask-1", "task"));
		repository.save(new TaskDefinition("myTask", "1: task && 2: task2"));
		repository.save(new TaskDefinition("myTask-2", "task"));

		assertThat(repository.count()).isEqualTo(3);
		mockMvc.perform(get("/tasks/definitions").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList", hasSize(3)));

		mockMvc.perform(delete("/tasks/definitions/myTask-1").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk());
		mockMvc.perform(delete("/tasks/definitions/myTask").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk());

		assertThat(repository.count()).isEqualTo(0);
	}

	@Test
	void ctrElementUpdate() throws Exception {
		repository.save(new TaskDefinition("a1", "t1: task && t2: task2"));
		repository.save(new TaskDefinition("a2", "task"));
		repository.save(new TaskDefinition("a1-t1", "task"));
		repository.save(new TaskDefinition("a1-t2", "task"));

		mockMvc.perform(get("/tasks/definitions").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList", hasSize(4)))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[0].name", is("a1")))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[0].composedTaskElement", is(false)))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[1].name", is("a2")))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[1].composedTaskElement", is(false)))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[2].name", is("a1-t1")))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[2].composedTaskElement", is(true)))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[3].name", is("a1-t2")))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[3].composedTaskElement", is(true)));

		mockMvc.perform(get("/tasks/definitions/a1-t2").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
				.andExpect(jsonPath("$.name", is("a1-t2")))
				.andExpect(jsonPath("$.composedTaskElement", is(true)));
	}

	@Test
	void ctrElementUpdateValidate() throws Exception {
		repository.save(new TaskDefinition("a1", "t1: task --foo='a|b' && t2: task2"));
		repository.save(new TaskDefinition("a2", "task"));
		repository.save(new TaskDefinition("a1-t1", "task"));
		repository.save(new TaskDefinition("a1-t2", "task"));

		mockMvc.perform(get("/tasks/definitions").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList", hasSize(4)))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[0].name", is("a1")))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[0].composedTaskElement", is(false)))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[1].name", is("a2")))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[1].composedTaskElement", is(false)))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[2].name", is("a1-t1")))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[2].composedTaskElement", is(true)))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[3].name", is("a1-t2")))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[3].composedTaskElement", is(true)));

		mockMvc.perform(get("/tasks/definitions/a1-t2").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
				.andExpect(jsonPath("$.name", is("a1-t2")))
				.andExpect(jsonPath("$.composedTaskElement", is(true)));
	}

	@Test
	void missingApplication() throws Exception {
		repository.save(new TaskDefinition("myTask", "no-such-task-app"));

		mockMvc.perform(post("/tasks/executions").param("name", "myTask").accept(MediaType.APPLICATION_JSON))
				.andExpect(status().is5xxServerError())
				.andExpect(jsonPath("_embedded.errors[0].message", is("Unknown task app: no-such-task-app")))
				.andExpect(jsonPath("_embedded.errors[0].logref", is("IllegalArgumentException")));
	}

	@Test
	void taskNotDefined() throws Exception {
		mockMvc.perform(post("/tasks/executions")
						.param("name", "myFoo").accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("_embedded.errors[0].message", is("Could not find task definition named myFoo")))
				.andExpect(jsonPath("_embedded.errors[0].logref", is("NoSuchTaskDefinitionException")));
	}

	@Test
	void launch() throws Exception {
		repository.save(new TaskDefinition("myTask", "foo"));
		this.registry.save("foo", ApplicationType.task,
				"1.0.0", new URI("file:src/test/resources/apps/foo-task"), null);

		mockMvc.perform(post("/tasks/executions").param("name", "myTask").accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isCreated());

		ArgumentCaptor<AppDeploymentRequest> argumentCaptor = ArgumentCaptor.forClass(AppDeploymentRequest.class);
		verify(this.taskLauncher, atLeast(1)).launch(argumentCaptor.capture());

		AppDeploymentRequest request = argumentCaptor.getValue();
		assertThat(request.getDefinition().getProperties()).containsEntry("spring.cloud.task.name", "myTask");

		mockMvc.perform(delete("/tasks/definitions").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk());

		// Destroy should be called only if there was a launch task
		Mockito.verify(taskLauncher).destroy("myTask");
	}

	@Test
	void launchWithDefaultApplicationPropertiesYamlResource() throws Exception {
		testLaunchWithCommonProperties(new DefaultResourceLoader().getResource(
				"classpath:/defaults/test-application-task-common-properties-defaults.yml"));
	}

	@Test
	void launchWithDefaultApplicationPropertiesPropertyResource() throws Exception {
		testLaunchWithCommonProperties(new DefaultResourceLoader().getResource(
				"classpath:/defaults/test-application-task-common-properties-defaults.properties"));
	}

	private void testLaunchWithCommonProperties(Resource newResource) throws Exception {

		Resource oldResource = appsProperties.getTaskResource();

		try {
			appsProperties.setTaskResource(newResource);

			repository.save(new TaskDefinition("myTask", "foo"));
			this.registry.save("foo", ApplicationType.task,
					"1.0.0", new URI("file:src/test/resources/apps/foo-task"), null);

			mockMvc.perform(post("/tasks/executions").param("name", "myTask").accept(MediaType.APPLICATION_JSON))
					.andExpect(status().isCreated());

			ArgumentCaptor<AppDeploymentRequest> argumentCaptor = ArgumentCaptor.forClass(AppDeploymentRequest.class);
			verify(this.taskLauncher, atLeast(1)).launch(argumentCaptor.capture());

			AppDeploymentRequest request = argumentCaptor.getValue();
			assertThat(request.getDefinition().getProperties()).containsEntry("spring.cloud.task.name", "myTask");
			assertThat(request.getDefinition().getProperties()).containsEntry("my.test.static.property", "Test");
			assertThat(request.getDefinition().getProperties()).containsEntry("my.test.property.with.placeholder", "${my.placeholder}");

			mockMvc.perform(delete("/tasks/definitions").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk());

			// Destroy should be called only if there was a launch task
			Mockito.verify(taskLauncher).destroy("myTask");
		} finally {
			appsProperties.setTaskResource(oldResource);
		}
	}

	@Test
	void launchWithAppProperties() throws Exception {

		repository.save(new TaskDefinition("myTask2", "foo2 --common.prop2=wizz"));
		this.registry.save("foo2", ApplicationType.task,
				"1.0.0", new URI("file:src/test/resources/apps/foo-task"), null);

		mockMvc.perform(post("/tasks/executions").param("name", "myTask2")
						.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isCreated());

		ArgumentCaptor<AppDeploymentRequest> argumentCaptor = ArgumentCaptor.forClass(AppDeploymentRequest.class);
		verify(this.taskLauncher, atLeast(1)).launch(argumentCaptor.capture());

		AppDeploymentRequest request = argumentCaptor.getValue();
		assertThat(request.getDefinition().getProperties()).containsEntry("common.prop2", "wizz");
		assertThat(request.getDefinition().getProperties()).containsEntry("spring.cloud.task.name", "myTask2");
	}

	@Test
	void launchWithArguments() throws Exception {
		repository.save(new TaskDefinition("myTask3", "foo3"));
		this.registry.save("foo3", ApplicationType.task,
				"1.0.0", new URI("file:src/test/resources/apps/foo-task"), null);

		mockMvc.perform(post("/tasks/executions")
						.contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.content(EntityUtils.toString(new UrlEncodedFormEntity(Arrays.asList(
								new BasicNameValuePair("name", "myTask3"),
								new BasicNameValuePair("arguments",
										"--foobar=jee --foobar2=jee2,foo=bar --foobar3='jee3 jee3'")))))
						.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isCreated());

		ArgumentCaptor<AppDeploymentRequest> argumentCaptor = ArgumentCaptor.forClass(AppDeploymentRequest.class);
		verify(this.taskLauncher, atLeast(1)).launch(argumentCaptor.capture());

		AppDeploymentRequest request = argumentCaptor.getValue();
		assertThat(request.getCommandlineArguments()).hasSize(4);
		// don't assume order in a list
		assertThat(request.getCommandlineArguments()).contains("--foobar=jee", "--foobar2=jee2,foo=bar", "--foobar3='jee3 jee3'");
		assertThat(request.getDefinition().getProperties()).containsKey("spring.cloud.task.name");
	}

	@Test
	void displaySingleTask() throws Exception {
		TaskDefinition taskDefinition = new TaskDefinition("myTask", "timestamp --password=password");
		repository.save(taskDefinition);

		TaskDefinition taskDefinition2 = new TaskDefinition("myTask2", "timestamp --regular=value");
		repository.save(taskDefinition2);

		TaskDefinition taskDefinition3 = new TaskDefinition("myTask3", "timestamp");
		repository.save(taskDefinition3);

		assertThat(repository.count()).isEqualTo(3);

		verifyTaskArgs(
				SAMPLE_CLEANSED_ARGUMENT_LIST,
				"$.lastTaskExecution.",
				mockMvc
						.perform(get("/tasks/definitions/myTask").accept(MediaType.APPLICATION_JSON))
						.andExpect(status().isOk())
						.andExpect(content().json("{name: \"myTask\"}"))
						.andExpect(content().json("{status: \"RUNNING\"}"))
						.andExpect(content().json("{dslText: \"timestamp --password='******'\"}")));

		verifyTaskArgs(
				SAMPLE_CLEANSED_ARGUMENT_LIST,
				"$.lastTaskExecution.",
				mockMvc
						.perform(get("/tasks/definitions/myTask2").accept(MediaType.APPLICATION_JSON))
						.andExpect(status().isOk())
						.andExpect(content().json("{name: \"myTask2\"}"))
						.andExpect(content().json("{status: \"COMPLETE\"}"))
						.andExpect(content().json("{dslText: \"timestamp --regular=value\"}")));

		mockMvc
				.perform(get("/tasks/definitions/myTask3").accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isOk())
				.andExpect(content().json("{name: \"myTask3\"}"))
				.andExpect(content().json("{status: \"UNKNOWN\"}"))
				.andExpect(content().json("{dslText: \"timestamp\"}"));
	}

	@Test
	void displaySingleTaskNotFound() throws Exception {
		mockMvc.perform(get("/tasks/definitions/myTask").accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isNotFound());
	}

	@Test
	void getAllTasks() throws Exception {
		TaskDefinition taskDefinition = new TaskDefinition("myTask", "timestamp --password=123");
		repository.save(taskDefinition);

		TaskDefinition taskDefinition2 = new TaskDefinition("myTask2", "timestamp --regular=value");
		repository.save(taskDefinition2);

		TaskDefinition taskDefinition3 = new TaskDefinition("myTask3", "timestamp");
		repository.save(taskDefinition3);

		assertThat(repository.count()).isEqualTo(3);

		verifyTaskArgs(SAMPLE_CLEANSED_ARGUMENT_LIST, "$._embedded.taskDefinitionResourceList[0].lastTaskExecution.",
				mockMvc.perform(get("/tasks/definitions").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
						.andDo(print()))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList", hasSize(3)))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[*].name",
						containsInAnyOrder("myTask", "myTask2", "myTask3")))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[*].dslText",
						containsInAnyOrder("timestamp --password='******'", "timestamp --regular=value", "timestamp")))
				.andExpect(jsonPath("$._embedded.taskDefinitionResourceList[*].status",
						containsInAnyOrder("RUNNING", "COMPLETE", "UNKNOWN")));
	}

	@Test
	void validate() throws Exception {
		repository.save(new TaskDefinition("myTask", "foo"));
		this.registry.save("foo", ApplicationType.task,
				"1.0.0", new URI("file:src/test/resources/apps/foo-task"), null);

		mockMvc.perform(get("/tasks/validation/myTask")).andExpect(status().isOk())
				.andExpect(content().json(
						"{\"appName\":\"myTask\",\"appStatuses\":{\"task:myTask\":\"valid\"},\"dsl\":\"foo\"}"));

	}

	@Test
	void taskLaunchNoManifest() throws Exception {
		final TaskExecution taskExecutionComplete = this.taskExecutionCreationService.createTaskExecution("myTask3", null);
		assertThat(taskExecutionComplete.getExecutionId()).isGreaterThan(0L);
		taskExecutionComplete.setTaskName("myTask3");
		taskExecutionComplete.setStartTime(LocalDateTime.now());
		taskExecutionComplete.setEndTime(LocalDateTime.now());
		taskExecutionComplete.setExitCode(0);
		repository.save(new TaskDefinition("myTask3", "foo"));
		this.registry.save("foo", ApplicationType.task,
				"1.0.0", new URI("file:src/test/resources/apps/foo-task"), null);
		dataflowTaskExecutionMetadataDao.save(taskExecutionComplete, null);
		mockMvc.perform(get("/tasks/definitions/myTask3").param("manifest", "true").accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isOk());

	}

	private ResultActions verifyTaskArgs(List<String> expectedArgs, String prefix, ResultActions ra) throws Exception {
		ra.andExpect(jsonPath(prefix + "arguments", hasSize(expectedArgs.size())));
		for (int argCount = 0; argCount < expectedArgs.size(); argCount++) {
			ra.andExpect(jsonPath(String.format(prefix + "arguments[%d]", argCount), is(expectedArgs.get(argCount))));
		}
		return ra;
	}
}
