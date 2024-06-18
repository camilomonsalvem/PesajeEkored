package de.kai_morich.simple_usb_terminal;

import androidx.fragment.app.FragmentManager;

public class FragmentManagerSingleton {
    private static FragmentManagerSingleton instance;
    private FragmentManager fragmentManager;

    private FragmentManagerSingleton() {
        // Constructor privado para evitar instanciación directa
    }

    public static synchronized FragmentManagerSingleton getInstance() {
        if (instance == null) {
            instance = new FragmentManagerSingleton();
        }
        return instance;
    }

    public void setFragmentManager(FragmentManager fragmentManager) {
        this.fragmentManager = fragmentManager;
    }

    public FragmentManager getFragmentManager() {
        return fragmentManager;
    }
}