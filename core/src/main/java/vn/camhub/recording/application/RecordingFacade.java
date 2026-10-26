package vn.camhub.recording.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.camhub.recording.api.RecordingApi;
import vn.camhub.recording.domain.RecordingPolicy;
import vn.camhub.recording.infrastructure.RecordingRepository;

@Service
class RecordingFacade implements RecordingApi {

    private final RecordingRepository repo;
    private final StorageMonitorJob storage;
    private final RecordingService recording;

    RecordingFacade(RecordingRepository repo, StorageMonitorJob storage, RecordingService recording) {
        this.repo = repo;
        this.storage = storage;
        this.recording = recording;
    }

    @Override
    public void applyPolicy(UUID actor, UUID cameraId, PolicySpec spec) {
        recording.setPolicy(actor, new RecordingPolicy(cameraId, RecordingPolicy.Mode.valueOf(spec.mode()),
                spec.schedule() == null ? List.of() : spec.schedule().stream()
                        .map(s -> new RecordingPolicy.Slot(s.day(), s.from(), s.to())).toList(),
                spec.preEventSeconds(), spec.postEventSeconds()));
    }

    @Override
    public boolean hasRecording(UUID cameraId, Instant from, Instant to) {
        return !repo.segments(cameraId, from, to).isEmpty();
    }

    @Override
    public List<StorageVolume> storageVolumes() {
        return storage.volumes();
    }
}
