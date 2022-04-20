package com.android.example.cameraxbasic

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.android.example.cameraxbasic.databinding.ActivityMainBinding
import com.android.example.cameraxbasic.databinding.CropImageActivityBinding

class CropImageActivity : AppCompatActivity() {

    companion object {
        val BUNDLE_PATH="path"
        fun startAct(activity: AppCompatActivity,path:String){
            val intent=Intent(activity,CropImageActivity::class.java)
            intent.putExtra(BUNDLE_PATH,path)
            activity.startActivity(intent)
        }
    }
    private lateinit var activityMainBinding: CropImageActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityMainBinding = CropImageActivityBinding.inflate(layoutInflater)
        setContentView(activityMainBinding.root)

    }

    //  zoom  -> tap to zoom  -> save
   fun intializeImage(){
   }
}