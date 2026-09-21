package ru.ainur.feldshernotes.audio

import java.io.*

/** AAC access units, with immutable byte boundaries and pinned files for concurrent saves. */
class PacketRing(private val directory: File) : Closeable {
    private class Segment(val file:File,val start:Long,var end:Long=start,var bytes:Long=0,var pins:Int=0)
    class Snapshot internal constructor(internal val files:List<Pair<File,Long>>,val startUs:Long,val endUs:Long)
    private val segments=mutableListOf<Segment>()
    private var output:DataOutputStream?=null
    init { check(directory.mkdirs() || directory.isDirectory) { "Не удалось создать временные файлы буфера" } }
    @Synchronized fun append(timeUs:Long,packet:ByteArray) {
        require(packet.isNotEmpty() && packet.size<=1024*1024)
        if(segments.isEmpty() || timeUs-segments.last().start>=30_000_000L){
            output?.close();val next=Segment(File(directory,"$timeUs.aac-packets"),timeUs)
            segments+=next;output=DataOutputStream(BufferedOutputStream(next.file.outputStream()))
        }
        val s=segments.last();output!!.writeLong(timeUs);output!!.writeInt(packet.size);output!!.write(packet)
        s.end=timeUs;s.bytes+=12+packet.size;prune()
    }
    @Synchronized fun snapshot():Snapshot {
        check(segments.isNotEmpty()){"Буфер ещё пуст"};output?.flush()
        val end=segments.last().end;val start=maxOf(segments.first().start,end-900_000_000L)
        val selected=segments.filter{it.end>=start};selected.forEach{it.pins++}
        return Snapshot(selected.map{it.file to it.bytes},start,end)
    }
    fun read(snapshot:Snapshot,consume:(Long,ByteArray)->Unit) {
        snapshot.files.forEach{(file,limit)->DataInputStream(BufferedInputStream(file.inputStream())).use{input->
            var remaining=limit
            while(remaining>0){check(remaining>=12);val pts=input.readLong();val size=input.readInt()
                check(size in 1..1024*1024 && size.toLong()+12<=remaining)
                val packet=ByteArray(size);input.readFully(packet);remaining-=12+size
                if(pts in snapshot.startUs..snapshot.endUs)consume(pts,packet)
            }
        }}
    }
    @Synchronized fun release(snapshot:Snapshot) {
        val files=snapshot.files.map{it.first}.toSet();segments.filter{it.file in files}.forEach{check(it.pins>0);it.pins--};prune()
    }
    @Synchronized fun durationMs():Long=if(segments.isEmpty())0 else minOf(900_000L,(segments.last().end-segments.first().start)/1000)
    private fun prune(){
        val cutoff=(segments.lastOrNull()?.end ?: return)-900_000_000L
        val old=segments.filter{it!==segments.last() && it.end<cutoff && it.pins==0}
        old.forEach{if(it.file.delete())segments.remove(it)}
    }
    @Synchronized override fun close(){output?.close();output=null}
}
