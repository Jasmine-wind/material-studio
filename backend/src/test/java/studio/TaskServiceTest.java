package studio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.nio.file.*;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class TaskServiceTest {
    @TempDir Path root;
    @Test void commitsBeforeDispatchAndPersistsRejection() throws Exception {
        var mapper=mock(StudioMapper.class); var templates=mock(Templates.class);
        var storage=new Storage(root.toString()); var runner=mock(TaskRunner.class);
        var executor=mock(ThreadPoolTaskExecutor.class); var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(templates.get("x")).thenReturn(new Models.Template("x","X","test","/x","",1,List.of("image/png"),100));
        var asset=new Models.Asset();asset.mimeType="image/png";asset.sizeBytes=3;asset.storageKey="input.png";
        Files.write(storage.upload("input.png"),new byte[]{1,2,3}); when(mapper.asset("a","alice")).thenReturn(asset);
        doThrow(new TaskRejectedException("full")).when(executor).execute(any(Runnable.class));
        var service=new TaskService(mapper,templates,storage,runner,executor,manager,true);
        assertThatThrownBy(()->service.create("alice",new Models.CreateTask("x",List.of("a"),null))).isInstanceOf(ApiError.class);
        var order=inOrder(manager,mapper,executor);
        order.verify(mapper).asset("a","alice");
        order.verify(manager).getTransaction(any()); order.verify(mapper).insertTask(any()); order.verify(mapper).link(anyString(),eq(0),eq("a"));
        order.verify(manager).commit(any()); order.verify(executor).execute(any(Runnable.class));
        order.verify(mapper).fail(anyString(),eq("NONE"),contains("服务繁忙"));
        verifyNoInteractions(runner);
        var disabled=new TaskService(mapper,templates,storage,runner,executor,manager,false);
        assertThatThrownBy(()->disabled.create("alice",new Models.CreateTask("x",List.of("a"),new Models.Simulation(null,0))))
                .isInstanceOf(ApiError.class).hasMessageContaining("未启用");
    }
}
