package uz.uni_team.date_time_manager

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Toast

class ExampleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_example)
        //TODO receive global message
        val data = intent.extras?.getString(Intent.EXTRA_TEXT)
        Toast.makeText(this, "$data", Toast.LENGTH_SHORT).show()
    }
}