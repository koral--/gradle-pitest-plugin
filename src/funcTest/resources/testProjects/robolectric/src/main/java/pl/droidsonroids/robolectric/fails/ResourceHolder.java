package pl.droidsonroids.robolectric.fails;

import android.content.Context;
import android.support.annotation.NonNull;

import pl.drodsonroids.pitest.R;

public class ResourceHolder {
    private final String _test;

    public ResourceHolder(@NonNull Context context) {
        _test = context.getString(R.string.test);
    }

    public String getTest() {
        return _test;
    }
}
