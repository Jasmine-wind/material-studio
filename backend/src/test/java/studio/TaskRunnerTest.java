package studio;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class TaskRunnerTest {
    @TempDir Path root;
    @ParameterizedTest @NullSource @ValueSource(strings={"CHECK_MATERIAL","GENERATE","FINALIZE"})
    void orderedIndependentStagesStopAtFailure(String failedStage) throws Exception {
        var mapper = mock(StudioMapper.class);
        var storage = new Storage(root.toString());
        var input = new Models.Asset(); input.storageKey="input.png"; input.mimeType="image/png";
        Files.write(storage.upload(input.storageKey),new byte[]{1,2,3});
        when(mapper.inputs("task")).thenReturn(List.of(input));
        when(mapper.startStage(anyString(),anyString(),anyString(),anyString())).thenReturn(1);
        var task = new Models.Task(); task.id="task"; task.simulateFailStage=failedStage;
        var failures = new FailureSettlement(mapper);
        failures.reserve(task.id);
        new TaskRunner(mapper,storage,failures).run(task);
        var order = inOrder(mapper);
        order.verify(mapper).inputs("task");
        String previous="NONE";
        for (String stage : TaskRunner.STAGES) {
            order.verify(mapper).startStage("task",previous,stage,previous.equals("NONE")?"QUEUED":"RUNNING");
            if (stage.equals(failedStage)) {
                order.verify(mapper).fail(eq("task"),eq(stage),contains(stage));
                verify(mapper,never()).succeed(anyString(),anyString());
                break;
            }
            previous=stage;
        }
        if (failedStage==null) { order.verify(mapper).succeed("task","task.png"); assertThat(storage.result("task.png")).exists(); }
        order.verifyNoMoreInteractions();
        assertThat(storage.temporary("task")).doesNotExist();
    }
    @Test void staleRunnerCannotAdvanceTerminalTask() throws Exception {
        var mapper=mock(StudioMapper.class);
        var input=new Models.Asset(); input.mimeType="image/png";
        when(mapper.inputs("task")).thenReturn(List.of(input));
        when(mapper.startStage(anyString(),anyString(),anyString(),anyString())).thenReturn(0);
        var task=new Models.Task();task.id="task";
        var failures = new FailureSettlement(mapper);
        failures.reserve(task.id);
        new TaskRunner(mapper,new Storage(root.toString()),failures).run(task);
        verify(mapper,never()).succeed(anyString(),anyString());
        verify(mapper,never()).fail(anyString(),anyString(),anyString());
    }
}
