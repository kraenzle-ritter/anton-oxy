package ch.kr.anton.oxy;

import ro.sync.exml.plugin.Plugin;
import ro.sync.exml.plugin.PluginDescriptor;

/**
 * Plugin entry point (boilerplate singleton required by Oxygen).
 */
public class AntonOxyPlugin extends Plugin {

    private static AntonOxyPlugin instance = null;

    public AntonOxyPlugin(PluginDescriptor descriptor) {
        super(descriptor);
        if (instance != null) {
            throw new IllegalStateException("Already instantiated!");
        }
        instance = this;
    }

    public static AntonOxyPlugin getInstance() {
        return instance;
    }
}
