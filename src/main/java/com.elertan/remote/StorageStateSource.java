package com.elertan.remote;

import com.elertan.utils.Observable;

public interface StorageStateSource {

    Observable<State> getState();

    enum State {
        NotReady,
        Ready
    }
}