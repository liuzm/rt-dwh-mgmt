package com.rtdwh.service;
import com.rtdwh.entity.TaskRunInstance.RunStatus;
import com.rtdwh.repository.TaskRunInstanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
@Service @RequiredArgsConstructor
public class DeliveryReconcileService {
    private final TaskRunInstanceRepository instances;
    private final DeliveryFinalizationService finalization;
    @Scheduled(fixedDelayString="${workflow.delivery.reconcile-ms:5000}", initialDelay=15000)
    public void reconcile() {
        for (var run : instances.findTop100ByStatusAndDeliveryStatusOrderById(RunStatus.success, "checking")) {
            try { finalization.finalizeInstance(run.getId()); }
            catch (Exception unavailable) { finalization.noteError(run.getId()); }
        }
    }
}
