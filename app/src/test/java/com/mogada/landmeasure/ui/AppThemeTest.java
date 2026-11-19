package com.mogada.landmeasure.ui;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.res.Configuration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class AppThemeTest {
    @Test public void systemChoiceFollowsNightFlagIncludingNonPhoneUiTypes() {
        Configuration configuration = new Configuration();
        configuration.uiMode = Configuration.UI_MODE_TYPE_CAR | Configuration.UI_MODE_NIGHT_YES;
        assertTrue(AppTheme.isDark(AppTheme.SYSTEM, configuration));
        configuration.uiMode = Configuration.UI_MODE_TYPE_NORMAL | Configuration.UI_MODE_NIGHT_NO;
        assertFalse(AppTheme.isDark(AppTheme.SYSTEM, configuration));
    }

    @Test public void explicitChoiceOverridesOppositePhoneTheme() {
        Configuration configuration = new Configuration();
        configuration.uiMode = Configuration.UI_MODE_NIGHT_YES;
        assertFalse(AppTheme.isDark(AppTheme.LIGHT, configuration));
        configuration.uiMode = Configuration.UI_MODE_NIGHT_NO;
        assertTrue(AppTheme.isDark(AppTheme.DARK, configuration));
    }
}
