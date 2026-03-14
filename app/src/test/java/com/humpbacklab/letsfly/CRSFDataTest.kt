package com.humpbacklab.letsfly
import org.junit.Test

import org.junit.Assert.*
class CRSFDataTest {
    @Test
    fun test(){
        val a=CRSFData()
        for (i in 1 .. 16){
            a.data_array[i-1]=i
        }
        val bytes=a.pack().toByteArray()
        for(i in bytes){
            print(String.format("%02X,", i))
        }
        println("")
        assertArrayEquals(bytes, byteArrayOf(0xC8.toByte(), 0x18, 0x16, 0x01, 0x10,
            0xC0.toByte(), 0x00, 0x08, 0x50, 0x00, 0x03, 0x1C, 0x00, 0x01, 0x09, 0x50,
            0xC0.toByte(), 0x02, 0x18, 0xD0.toByte(), 0x00, 0x07, 0x3C, 0x00, 0x02, 0xFC.toByte()
        ))
    }

    @Test
    fun test_cmd(){
        val a=CRSFData()
        println(a.packCmd(0x01u,0x09u))

    }

}