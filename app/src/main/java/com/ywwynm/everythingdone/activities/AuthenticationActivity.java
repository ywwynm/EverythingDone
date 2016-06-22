package com.ywwynm.everythingdone.activities;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;

import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.helpers.AuthenticationHelper;
import com.ywwynm.everythingdone.model.Thing;

public class AuthenticationActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_authentication);

        final Intent intent = getIntent();
        long id = intent.getLongExtra(Def.Communication.KEY_ID, -1);

        Thing thing = ThingDAO.getInstance(this).getThingById(id);
        if (thing == null) {
            finish();
            return;
        }

        if (thing.isPrivate()) {
            int color = thing.getColor();
            String cp = getSharedPreferences(Def.Meta.PREFERENCES_NAME, MODE_PRIVATE)
                    .getString(Def.Meta.KEY_PRIVATE_PASSWORD, null);
            AuthenticationHelper.authenticate(
                    this, color, getString(R.string.check_private_thing), cp,
                    new AuthenticationHelper.AuthenticationCallback() {
                        @Override
                        public void onAuthenticated() {
                            openDetailActivity(intent);
                        }

                        @Override
                        public void onCancel() {
                            finish();
                            overridePendingTransition(0, 0);
                        }
                    });
        } else {
            openDetailActivity(intent);
        }
    }

    private void openDetailActivity(Intent intent) {
        intent.setClass(this, DetailActivity.class);
        startActivity(intent);
        finish();
        overridePendingTransition(0, 0);
    }
}
