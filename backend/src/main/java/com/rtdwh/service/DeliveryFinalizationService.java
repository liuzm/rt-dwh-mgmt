package com.rtdwh.service;
import com.rtdwh.entity.TaskRunInstance;
import com.rtdwh.repository.TaskRunInstanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @RequiredArgsConstructor
public class DeliveryFinalizationService {
    private final TaskRunInstanceRepository instances;
    private final DatasetProductionService productions;
    @Transactional
    public void finalizeInstance(Long id) {
        TaskRunInstance run = instances.findByIdForUpdate(id).orElseThrow();
        if (run.getStatus() != TaskRunInstance.RunStatus.success || !"checking".equals(run.getDeliveryStatus())) return;
        productions.recordSuccess(run, true);
        run.setDeliveryStatus(productions.isDeliveryAvailable(run) ? "available" : "blocked");
        run.setDeliveryError("blocked".equals(run.getDeliveryStatus()) ? "产出未通过质量门禁，查看产出检测记录" : null);
        instances.save(run);
    }
    @Transactional
    public TaskRunInstance recheck(Long id) {
        TaskRunInstance run = instances.findByIdForUpdate(id).orElseThrow();
        if (run.getStatus() != TaskRunInstance.RunStatus.success || !"blocked".equals(run.getDeliveryStatus()))
            throw new IllegalStateException("只有计算成功且交付被阻断的实例可以重新检测");
        run.setDeliveryStatus("checking"); run.setDeliveryError(null);
        return instances.save(run);
    }
    @Transactional
    public void noteError(Long id) {
        TaskRunInstance run = instances.findByIdForUpdate(id).orElseThrow();
        if ("checking".equals(run.getDeliveryStatus())) {
            run.setDeliveryError("交付检查暂未完成，保留计算结果并等待重试"); instances.save(run);
        }
    }
}
