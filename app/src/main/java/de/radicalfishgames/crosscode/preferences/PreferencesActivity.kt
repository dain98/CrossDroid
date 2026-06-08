package de.radicalfishgames.crosscode.preferences

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import de.radicalfishgames.crosscode.R
import java.io.*
import java.lang.StringBuilder


class PreferencesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.actvitity_preferences)

        supportFragmentManager
            .beginTransaction()
            .replace(R.id.preferences_placeholder, PreferenceFragment())
            .commit()

    }
}

// For whatever reason AndroidX _requires_ us to use a fragment, even though it could just have the whole activity
class PreferenceFragment : PreferenceFragmentCompat() {
    companion object {
        const val FILE_PICKER_REQUEST_CODE = 10
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        val filePicker = findPreference("fileToImport") as Preference?
        filePicker!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "text/plain"
            startActivityForResult(intent, FILE_PICKER_REQUEST_CODE)
            true
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if(requestCode == FILE_PICKER_REQUEST_CODE) {
            val preferences: SharedPreferences =
                requireContext().getSharedPreferences("de.radicalfishgames.crosscode_preferences", Context.MODE_PRIVATE)

            val contentDescriber: Uri? = data?.data
            var reader: BufferedReader? = null
            try {
                // open the user-picked file for reading:
                val inputStream: InputStream? =
                    contentDescriber?.let { activity!!.contentResolver.openInputStream(it) }
                // now read the content:
                reader = BufferedReader(InputStreamReader(inputStream))
                var line: String?
                val builder = StringBuilder()
                while (reader.readLine().also { line = it } != null) {
                    builder.append(line)
                }
                // Do something with the content in
                preferences.edit()
                    .putString("save_to_import", builder.toString())
                    .apply()
            } catch (e: FileNotFoundException) {
                e.printStackTrace()
            } catch (e: IOException) {
                e.printStackTrace()
            } finally {
                if (reader != null) {
                    try {
                        reader.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

}