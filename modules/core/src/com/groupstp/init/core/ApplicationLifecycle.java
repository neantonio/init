package com.groupstp.init.core;


import com.haulmont.cuba.core.global.AppBeans;
import com.haulmont.cuba.core.sys.AppContext;
import org.springframework.stereotype.Component;

import javax.inject.Inject;

/**
 * This bean is listening application lifecycle
 *
 * @author adiatullin
 */
@Component("init_ApplicationLifecycle")
public class ApplicationLifecycle implements AppContext.Listener {



    public ApplicationLifecycle() {
        AppContext.addListener(this);
    }

    @Override
    public void applicationStarted() {
        AppBeans.get(InitializationBean.class).init();
    }

    @Override
    public void applicationStopped() {
    }
}