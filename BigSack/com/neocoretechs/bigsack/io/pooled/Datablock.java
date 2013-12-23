package com.neocoretechs.bigsack.io.pooled;
import java.io.IOException;

import com.neocoretechs.bigsack.DBPhysicalConstants;
import com.neocoretechs.bigsack.Props;
import com.neocoretechs.bigsack.io.IoInterface;
/*
* Copyright (c) 1997,2003, NeoCoreTechs
* All rights reserved.
* Redistribution and use in source and binary forms, with or without modification, 
* are permitted provided that the following conditions are met:
*
* Redistributions of source code must retain the above copyright notice, this list of
* conditions and the following disclaimer. 
* Redistributions in binary form must reproduce the above copyright notice, 
* this list of conditions and the following disclaimer in the documentation and/or
* other materials provided with the distribution. 
* Neither the name of NeoCoreTechs nor the names of its contributors may be 
* used to endorse or promote products derived from this software without specific prior written permission. 
* THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED 
* WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A 
* PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR 
* ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
* TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
* HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT 
* (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED 
* OF THE POSSIBILITY OF SUCH DAMAGE.
*
*/
/**
* Datablock - Class for doubly linked list DB block (page)
* composed of header and data payload whose total size is the constant DBLOCKSIZ
* @author Groff
*/
public final class Datablock {
	private long prevblk = -1L; // offset to prev blk in chain
	private long nextblk = -1L; // offset of next blk in chain
	private short bytesused; // bytes used this blk-highwater mark
	private short bytesinuse; // actual # of bytes in use
	private long writeid; // transaction id of writer
	private long version = -1L; // version number of this block
	byte data[]; // data section of blk
	private boolean incore = false; // is it modified?
	//
	private int datasize;
	//
	public Datablock(int tdatasize) {
		datasize = tdatasize;
		data = new byte[datasize];
	}
	/**
	* Write the header and data portion to IoInterface implementor.
	* Primarily for freechain create as we used writeUsed otherwise
	* @param fobj the IoInterface
	* @exception IOException error writing field
	*/
	public void write(IoInterface fobj) throws IOException {
		fobj.Fwrite_long(getPrevblk());
		fobj.Fwrite_long(getNextblk());
		fobj.Fwrite_short(getBytesused());
		fobj.Fwrite_short(getBytesinuse());
		fobj.Fwrite_long(getWriteid());
		fobj.Fwrite_long(getVersion());
		fobj.Fwrite(data);
	}
	/**
	* write the header and used data portion to IoInterface implementor
	* @param fobj the IoInterface
	* @exception IOException error writing field
	*/
	public void writeUsed(IoInterface fobj) throws IOException {
		fobj.Fwrite_long(getPrevblk());
		fobj.Fwrite_long(getNextblk());
		fobj.Fwrite_short(getBytesused());
		fobj.Fwrite_short(getBytesinuse());
		fobj.Fwrite_long(getWriteid());
		fobj.Fwrite_long(getVersion());
		if (getBytesused() == datasize)
			fobj.Fwrite(data);
		else
			fobj.Fwrite(data, getBytesused());
	}
	/**
	* write the header and data portion to IoInterface implementor
	* in compressed form
	* @param fobj the IoInterface
	* @exception IOException error writing field
	*/
	/*        protected synchronized void writeCompressed(IoInterface fobj) throws IOException
	        {
	                fobj.Fwrite_long(prevblk);
	                fobj.Fwrite_long(nextblk);
	                fobj.Fwrite_short(bytesused);
	                fobj.Fwrite_short(bytesinuse);
	                fobj.Fwrite_long(writeid);
	                fobj.Fwrite_long(version);
	                ByteArrayOutputStream baos = new ByteArrayOutputStream();
	                DeflaterOutputStream dfo = new DeflaterOutputStream(baos);
	                dfo.write(data,0,data.length);
	                dfo.close();
	                baos.close();
	                byte[] bx = baos.toByteArray();
	                fobj.Fwrite(bx);
	        }
	*/
	/**
	* read the header and data portion from IoInterface implementor
	* @param fobj the IoInterface
	* @exception IOException error reading field
	*/
	public void read(IoInterface fobj) throws IOException {
		setPrevblk(fobj.Fread_long());
		setNextblk(fobj.Fread_long());
		setBytesused(fobj.Fread_short());
		setBytesinuse(fobj.Fread_short());
		setWriteid(fobj.Fread_long());
		setVersion(fobj.Fread_long());
		if (fobj.Fread(data, datasize) != datasize) {
			throw new IOException(
				"Datablock read size invalid " + this.toString());
		}
	}
	/**
	* read the header and used data portion from IoInterface implementor
	* @param fobj the IoInterface
	* @exception IOException error reading field
	*/
	public void readUsed(IoInterface fobj) throws IOException {
		setPrevblk(fobj.Fread_long());
		setNextblk(fobj.Fread_long());
		setBytesused(fobj.Fread_short());
		setBytesinuse(fobj.Fread_short());
		setWriteid(fobj.Fread_long());
		setVersion(fobj.Fread_long());
		if (getBytesused() > datasize) {
			throw new IOException("block inconsistency " + this.toString());
		}
		if (getBytesused() == datasize) {
			if (fobj.Fread(data) != datasize) {
				throw new IOException(
					"Datablock read size invalid " + this.toString());
			}
		} else {
			if (fobj.Fread(data, getBytesused()) != getBytesused()) {
				throw new IOException(
					"Datablock read error bytesused="
						+ String.valueOf(getBytesused())
						+ " bytesinuse="
						+ String.valueOf(getBytesinuse()));
			}
		}
	}
	
	/** for debugging, write block info */
	void blockdump() {
		if( Props.DEBUG ) System.out.println(this.toString());
	}


	/**
	* deep copy
	* @return The clone of this block
	*/
	Datablock doClone() {
		Datablock d = new Datablock(DBPhysicalConstants.DATASIZE);
		d.setPrevblk(prevblk);
		d.setNextblk(nextblk);
		d.setBytesused(bytesused);
		d.setBytesinuse(bytesinuse);
		//
		if (getWriteid() != 0L) {
			throw new RuntimeException(
				"Attempt to clone block under write " + getWriteid());
		}

		System.arraycopy(data, 0, d.data, 0, getBytesused());
		d.setIncore(true);
		return d;
	}
	/**
	* deep copy
	* @param d The block to copy here
	*/
	public void doClone(Datablock d) {
		d.setPrevblk(prevblk);
		d.setNextblk(nextblk);
		d.setBytesused(bytesused);
		d.setBytesinuse(bytesinuse);

		d.setWriteid(writeid);
		d.setVersion(version);
		System.arraycopy(data, 0, d.data, 0, getBytesused());
		d.setIncore(true);
	}
	public String toString() {
		//String o = new String("Elems all 0");
		//for(int i =0;i<datasize;i++) {
		//        if(data[i] != 0) {
		//                o = new String("Some elems not 0");
		//                break;
		//        }
		//} o+=
		String o =
			"prev = "
				+ getPrevblk()
				+ " next = "
				+ getNextblk()
				+ " bytesused = "
				+ getBytesused()
				+ " bytesinuse = "
				+ bytesinuse
				+ " writeid= "
				+ getWriteid()
				+ " version: "
				+ getVersion()
				+ " incore "
				+ isIncore();
		return o;
	}
	public short getBytesinuse() {
		return bytesinuse;
	}
	public void setBytesinuse(short bytesinuse) {
		this.bytesinuse = bytesinuse;
	}
	public String toBriefString() {
		return ( getPrevblk() !=-1 || getNextblk() !=-1 || getBytesused() != 0 || bytesinuse != 0 || 
				getWriteid() !=0 || getVersion() != -1 || isIncore()) ?
				"prev = "
					+ getPrevblk()
					+ " next = "
					+ getNextblk()
					+ " bytesused = "
					+ getBytesused()
					+ " bytesinuse = "
					+ bytesinuse
					+ " writeid= "
					+ getWriteid()
					+ " version: "
					+ getVersion()
					+ " incore "
					+ isIncore()
			: "";
	}
	public String toVblockBriefString() {
		return ( getPrevblk() !=-1 || getNextblk() !=-1 || getBytesused() != 0 || bytesinuse != 0 || 
				getWriteid() !=0 || getVersion() != -1 || isIncore()) ?
				"prev = "
					+ GlobalDBIO.valueOf(getPrevblk())
					+ " next = "
					+ GlobalDBIO.valueOf(getNextblk())
					+ " bytesused = "
					+ getBytesused()
					+ " bytesinuse = "
					+ bytesinuse
					+ " writeid= "
					+ getWriteid()
					+ " version: "
					+ getVersion()
					+ " incore "
					+ isIncore()
			: "";
	}
	public boolean isIncore() {
		return incore;
	}
	public void setIncore(boolean incore) {
		this.incore = incore;
	}
	public long getPrevblk() {
		return prevblk;
	}
	public void setPrevblk(long prevblk) {
		this.prevblk = prevblk;
	}
	public long getNextblk() {
		return nextblk;
	}
	public void setNextblk(long nextblk) {
		this.nextblk = nextblk;
	}
	public short getBytesused() {
		return bytesused;
	}
	public void setBytesused(short bytesused) {
		this.bytesused = bytesused;
	}
	public long getWriteid() {
		return writeid;
	}
	public void setWriteid(long writeid) {
		this.writeid = writeid;
	}
	public long getVersion() {
		return version;
	}
	public void setVersion(long version) {
		this.version = version;
	}
}
