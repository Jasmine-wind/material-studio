package studio;

import org.apache.ibatis.annotations.*;
import java.util.List;
import static studio.Models.*;

@Mapper
public interface StudioMapper {
    @Insert("INSERT INTO assets(id,owner_id,original_name,mime_type,size_bytes,storage_key) VALUES(#{id},#{ownerId},#{originalName},#{mimeType},#{sizeBytes},#{storageKey})")
    void insertAsset(Asset asset);
    @Select("SELECT * FROM assets WHERE id=#{id} AND owner_id=#{owner}")
    Asset asset(String id, String owner);
    @Insert("INSERT INTO tasks(id,owner_id,template_id,status,stage,simulate_fail_stage,simulate_delay_ms) VALUES(#{id},#{ownerId},#{templateId},'QUEUED','NONE',#{simulateFailStage},#{simulateDelayMs})")
    void insertTask(Task task);
    @Insert("INSERT INTO task_assets(task_id,position,asset_id) VALUES(#{taskId},#{position},#{assetId})")
    void link(String taskId, int position, String assetId);
    @Select("SELECT * FROM tasks WHERE id=#{id} AND owner_id=#{owner}")
    Task task(String id, String owner);
    @Select("SELECT * FROM tasks WHERE owner_id=#{owner} ORDER BY created_at DESC, id DESC")
    List<Task> tasks(String owner);
    @Select("SELECT a.* FROM assets a JOIN task_assets ta ON a.id=ta.asset_id WHERE ta.task_id=#{taskId} ORDER BY ta.position")
    List<Asset> inputs(String taskId);
    @Update("UPDATE tasks SET status='RUNNING',stage=#{next},updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND stage=#{previous} AND status=#{expectedStatus}")
    int startStage(String id, String previous, String next, String expectedStatus);
    @Update("UPDATE tasks SET status='FAILED',failed_stage=#{stage},error_message=#{message},finished_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND status IN ('QUEUED','RUNNING')")
    int fail(String id, String stage, String message);
    @Update("UPDATE tasks SET status='SUCCEEDED',result_storage_key=#{key},finished_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND status='RUNNING' AND stage='FINALIZE'")
    int succeed(String id, String key);
}
