package org.hcjf.layers.storage.actions;

import org.hcjf.layers.storage.StorageSession;

/**
 * Select storage operation.
 * @author javaito
 *
 */
public abstract class Select<S extends StorageSession> extends StorageAction<S> {

    public Select(S session) {
        super(session);
    }

}
