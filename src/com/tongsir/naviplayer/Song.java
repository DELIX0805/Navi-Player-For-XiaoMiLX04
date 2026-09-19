package com.tongsir.naviplayer;

public class Song {
    public String id;
    public String title;
    public String artist;
    public String album;
    public String coverArt;
    public int duration;
    /** 文件码率（kbps），0 表示服务端未提供且无法推算 */
    public int bitRate;
    /** 原始文件格式（大写，如 MP3/FLAC/M4A），空串表示未知 */
    public String format;

    public Song(String id, String title, String artist, String album, String coverArt, int duration,
                int bitRate, String format) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.coverArt = coverArt;
        this.duration = duration;
        this.bitRate = bitRate;
        this.format = format == null ? "" : format;
    }
}
