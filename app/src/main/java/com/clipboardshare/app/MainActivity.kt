package com.clipboardshare.app

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Toast.makeText(this, "ClipboardShare установлено! Используйте меню «Поделиться» из других приложений.", Toast.LENGTH_LONG).show()
        finish()
    }
}
