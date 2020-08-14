package com.armcomptech.homeintrusiondetector;

import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.CheckBoxPreference;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import static com.armcomptech.homeintrusiondetector.CameraActivity.isValidEmail;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, new SettingsFragment())
                .commit();

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null){
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setDisplayShowHomeEnabled(true);
        }

    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // handle arrow click here
        if (item.getItemId() == android.R.id.home) {
            finish(); // close this activity and return to preview activity (if there is any)
        }

        return super.onOptionsItemSelected(item);
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {

            //settings stuff is edited here
            //root_preference is for backup
            final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());

            //created EditTextPreference for email to be entered in
            EditTextPreference enterEmailHere_EditText = new EditTextPreference(requireContext());
            enterEmailHere_EditText.setKey("enterEmailHere_EditText");
            enterEmailHere_EditText.setTitle("Click Here to add email address");
            enterEmailHere_EditText.setDialogTitle("Click Here to add email address");
            enterEmailHere_EditText.setSummary("Add Email Address(es) where you would like to be alerted");
            enterEmailHere_EditText.setPositiveButtonText("Add Email");
            enterEmailHere_EditText.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    if (isValidEmail(String.valueOf((String) newValue))) {
                        CameraActivity.addEmailAddress((String) newValue);
                    } else {
                        Toast.makeText(getContext(), "Enter a valid email Address", Toast.LENGTH_LONG).show();
                    }

                    return false;
                }
            });
            screen.addPreference(enterEmailHere_EditText);

            final PreferenceCategory contactsCategory = new PreferenceCategory(requireContext());
            contactsCategory.setTitle("Contacts");
            screen.addPreference(contactsCategory);

            for (String emailAddress : CameraActivity.getEmailAddresses()) {
                CheckBoxPreference emailCheckBoxPreference = new CheckBoxPreference(requireContext());
                emailCheckBoxPreference.setTitle(emailAddress);
                emailCheckBoxPreference.setChecked(true);
                emailCheckBoxPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                    if (!((Boolean) newValue)) {
                        CameraActivity.removeEmailAddress((String) preference.getTitle());
                    }
                    return false;
                });
                contactsCategory.addPreference(emailCheckBoxPreference);
            }

            final PreferenceCategory audioMonitoringCategory = new PreferenceCategory(requireContext());
            audioMonitoringCategory.setTitle("Audio Monitoring");
            screen.addPreference(audioMonitoringCategory);

            CheckBoxPreference glassBreakingCheckBoxPreference = new CheckBoxPreference(requireContext());
            glassBreakingCheckBoxPreference.setTitle("Get Alerted for glass breaking");
            glassBreakingCheckBoxPreference.setKey("glass_breaking");
            glassBreakingCheckBoxPreference.setSummary("An email will be sent to the contacts listed above if there is glass breaking sound detected");
            glassBreakingCheckBoxPreference.setChecked(true);

            CheckBoxPreference doorbellCheckBoxPreference = new CheckBoxPreference(requireContext());
            doorbellCheckBoxPreference.setTitle("Get Alerted for doorbell");
            doorbellCheckBoxPreference.setKey("doorbell");
            doorbellCheckBoxPreference.setSummary("An email will be sent to the contacts listed above if there is doorbell sound detected");
            doorbellCheckBoxPreference.setChecked(true);

            CheckBoxPreference doorKnockCheckBoxPreference = new CheckBoxPreference(requireContext());
            doorKnockCheckBoxPreference.setTitle("Get Alerted for door knocks");
            doorKnockCheckBoxPreference.setKey("knock");
            doorKnockCheckBoxPreference.setSummary("An email will be sent to the contacts listed above if the sound of a door knock is detected");
            doorKnockCheckBoxPreference.setChecked(true);

            audioMonitoringCategory.addPreference(glassBreakingCheckBoxPreference);
            audioMonitoringCategory.addPreference(doorbellCheckBoxPreference);
            audioMonitoringCategory.addPreference(doorKnockCheckBoxPreference);
            screen.addPreference(audioMonitoringCategory);


            final PreferenceCategory videoMonitoringCategory = new PreferenceCategory(requireContext());
            videoMonitoringCategory.setTitle("Video Monitoring");
            screen.addPreference(videoMonitoringCategory);

            CheckBoxPreference personDetectionCheckBoxPreference = new CheckBoxPreference(requireContext());
            personDetectionCheckBoxPreference.setTitle("Get Alerted for person detection");
            personDetectionCheckBoxPreference.setKey("person_detection");
            personDetectionCheckBoxPreference.setSummary("An email will be sent to the contacts listed above if a person is detected in the frame");
            personDetectionCheckBoxPreference.setChecked(true);

            videoMonitoringCategory.addPreference(personDetectionCheckBoxPreference);
            screen.addPreference(videoMonitoringCategory);

            setPreferenceScreen(screen);

//            setPreferencesFromResource(R.xml.root_preferences, rootKey);
        }
    }
}