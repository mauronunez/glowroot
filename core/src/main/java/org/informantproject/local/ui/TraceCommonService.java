/**
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.informantproject.local.ui;

import java.io.IOException;

import javax.annotation.Nullable;

import org.informantproject.core.trace.Trace;
import org.informantproject.core.trace.TraceRegistry;
import org.informantproject.core.util.ByteStream;
import org.informantproject.local.trace.TraceSnapshot;
import org.informantproject.local.trace.TraceSnapshotDao;
import org.informantproject.local.trace.TraceSnapshots;

import com.google.common.base.Ticker;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class TraceCommonService {

    private final TraceSnapshotDao traceSnapshotDao;
    private final TraceRegistry traceRegistry;
    private final Ticker ticker;

    @Inject
    public TraceCommonService(TraceSnapshotDao traceSnapshotDao, TraceRegistry traceRegistry,
            Ticker ticker) {

        this.traceSnapshotDao = traceSnapshotDao;
        this.traceRegistry = traceRegistry;
        this.ticker = ticker;
    }

    @Nullable
    ByteStream getSnapshotOrActiveJson(String id, boolean includeDetail) throws IOException {
        // check active traces first to make sure that the trace is not missed if it should complete
        // after checking stored traces but before checking active traces
        for (Trace active : traceRegistry.getTraces()) {
            if (active.getId().equals(id)) {
                TraceSnapshot snapshot = TraceSnapshots.from(active, ticker.read(), includeDetail);
                return TraceSnapshots.toByteStream(snapshot, includeDetail);
            }
        }
        TraceSnapshot snapshot = traceSnapshotDao.readSnapshot(id);
        if (snapshot == null) {
            return null;
        } else {
            return TraceSnapshots.toByteStream(snapshot, includeDetail);
        }
    }

}
