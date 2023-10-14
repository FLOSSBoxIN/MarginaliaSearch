package nu.marginalia.mqapi.loading;

import lombok.AllArgsConstructor;
import nu.marginalia.storage.model.FileStorageId;

import java.util.List;

@AllArgsConstructor
public class LoadRequest {
    public List<FileStorageId> inputProcessDataStorageIds;
}
