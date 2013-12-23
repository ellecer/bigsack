package com.neocoretechs.bigsack.io;
import java.io.IOException;

import com.neocoretechs.bigsack.DBPhysicalConstants;
import com.neocoretechs.bigsack.Props;
import com.neocoretechs.bigsack.io.pooled.BlockAccessIndex;
import com.neocoretechs.bigsack.io.pooled.BlockDBIO;
import com.neocoretechs.bigsack.io.pooled.Datablock;

/*
* Copyright (c) 1997,2002,2003, NeoCoreTechs
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
* UndoLog - manage undo log file <br>
* when writing block check mirrorblock if < 0 (-1) <br>
* -find end of trans log <br>
* -seek and read curblock (original form, not modified in mem version) <br>
* -set mirrorblock in read curblock to curblock <br>
* -write curblock to log <br>
* -set mirrorblock in db.curblock to log end <br>
* -return and write block <br>
*<p>
* at end (if commit) <br>
* -scan trans log <br>
* -set mirrorblock in orig to -1 <br>
* -delete undo log file <br>
*<p>
* at start (file open) or Rollback <br>
* -check for undo log existance, if existing <br>
* -put blocks back to orig mirrorblock <br>
* -setting mirrorblock to -1 <br>
* -reset EOF marker to orig. end <br>
*<p>
* new blocks not written to undo log <br>
* in DB: <br>
* if mirrorblock 0 new block or freechain block <br>
* if mirrorblock > 0 block in undo log <br>
* if mirrorblock < 0 block not yet copied this transaction <br>
* in undo log: <br>
* if mirrorblock > 0 original block in DB <br>
* 
* numKeys are the number of keys in the persisted table, saved in case we need restore
* numEntries are the number of entries in the undoLog
*
* @author Groff
*/

public final class UndoLog  {
	private Datablock dblk; // utility blocks
	private FileIO ulog; // the undo log file io obj
	private String Name; // undo log name dbname+.log
	private long originalBlkNum; // original block from db
	private int numEntries; // Number of UndoLog entries this transaction
	private long numKeys; // Persisted total number keys in db
	private BlockDBIO blockIO;
	private BlockAccessIndex tmpBai;

	public UndoLog(BlockDBIO tglobalio) throws IOException {
		blockIO = tglobalio;
		tmpBai = new BlockAccessIndex(); // template for search
		Name = new String(blockIO.getDBName() + ".log");
		// 
		ulog = new FileIO(Name, true);
		dblk = new Datablock(DBPhysicalConstants.DATASIZE);
		//bblk = new Datablock(DBPhysicalConstants.DATASIZE);

		if (ulog.Fsize() > 0L) {
			read();
		} else {
			clear();
		}
		if (numEntries > 0) {
			rollBack();
		}
		ulog.Fclose();
	}

	public long getNumKeys() {
		return numKeys;
	}
	
	public void setNumKeys(int tnumKeys) {
		numKeys = tnumKeys;
	}

	/**
	* Write log entry - uses current db.
	* This is initiated before buffer pool block flush (writeblk)
	* @param tlbn The long block number to log
	* @param tdb The block instance to log
	* @exception IOException if cannot open or write
	*/
	public void writeLog(long tlbn, Datablock tdb) throws IOException {
		ulog = new FileIO(Name, true);
		// check mirrorblock if < 0 (-1) if not, we have orig
		// copy in log already
		if (tdb.getVersion() != -1L)
			return;
		// find end of trans log
		long xsize = ulog.Fsize();
		if (xsize < numEntries * DBPhysicalConstants.DBLOCKSIZ)
			throw new IOException(
				"Log file corrupted; delete it. Its size is less than 1 block and it says it has " + numEntries+ " entries");
		xsize = 12 + (numEntries * DBPhysicalConstants.DBLOCKSIZ);
		// seek and read curblock (original form)
		blockIO.FseekAndRead(tlbn, dblk);
		// set mirrorblock in read curblock to curblock
		dblk.setVersion(tlbn);
		// write curblock to log
		ulog.Fseek(xsize);
		dblk.write(ulog);
		// set mirrorblock in db.curblock to log end
		// this is being called from write, so it gets updated
		tdb.setVersion(xsize);
		++numEntries;
		ulog.Fseek(0L);
		ulog.Fwrite_int(numEntries);
		ulog.Fclose();
		return;
	}
	/**
	 * Reset the version in each block, the version points to the block in the log
	 * If the block is in used list set version to 1 and wait for flush
	 * 
	 * @throws IOException
	 */
	public void Commit() throws IOException {
		ulog = new FileIO(Name, false);
		ulog.Fseek(12L);
		for (int i = 0; i < numEntries; i++) {
			// read mirror from log, set orig blk mirror to -1
			dblk.read(ulog);
			// See if in the buffer, if so reset
			// does not matter if incore, ResetVersion goes to disk direct
			tmpBai.setTemplateBlockNumber(dblk.getVersion());
			BlockAccessIndex bai =
				(BlockAccessIndex) (blockIO.getUsedBlock(tmpBai));
			if (bai != null)
				bai.getBlk().setVersion(-1L);
		}
		clearIfModified();
		ulog.Fclose();
	}
	
	/**
	 * Version of method called when starting and we see an undolog ready to restore 
	 * @throws IOException
	 */
	private void rollBack() throws IOException {
		ulog = new FileIO(Name, false);
		// put em' back
		read();
		if( Props.DEBUG ) System.out.println("Rollback "+numKeys+" keys"+numEntries+" entries");
		for (int i = 0; i < numEntries; i++) {
			dblk.read(ulog);
			originalBlkNum = dblk.getVersion();
			dblk.setVersion(-1);
			// consistence check, if our main
			// block does not have this pointer, dont overwrite it
			//blockIO.FseekAndRead(originalBlkNum, bblk);
			//if( bblk.version != ulog.Ftell() )
			//       if( Props.DEBUG ) System.out.println("Recovery inconsistency "+originalBlkNum+" "+bblk+" "+ulog.Ftell()+" "+dblk);
			//else
			// on the other hand, number of entries wont get updated
			// till this is successful at writing, so no bad entry
			// should make it in here
			if( Props.DEBUG ) System.out.println("Block #:"+originalBlkNum+" "+dblk);
			blockIO.FseekAndWriteNoLog(originalBlkNum, dblk);
		}
		// restore original keys count, we failed and we are starting from beginning in recovery mode
		// if there is a non zero value assume we have to restore it
		//if( numKeys != 0 )
		//	blockIO.getKeycountfile().writeKeysCount(numKeys);
		blockIO.Fforce(); // synch main file buffs
		clearIfModified();
		ulog.Fclose();
	}
	
	private void read() throws IOException {
		ulog.Fseek(0L);
		numEntries = ulog.Fread_int();
		numKeys = ulog.Fread_long();		
	}

	/**
	* This operation reads restored blocks to cache if they exist
	* therein
	* @exception IOException If we can't replace blocks
	*/
	public void rollBackCache() throws IOException {
		ulog = new FileIO(Name, true);
		// put em' back
		read();
		if( Props.DEBUG ) System.out.println("Rollback cache "+numEntries+" entries, "+numKeys+" keys");
		for (int i = 0; i < numEntries; i++) {
			dblk.read(ulog); // read it from log
			originalBlkNum = dblk.getVersion(); // get original block number
			dblk.setVersion(-1); // set it so it is not in undo log anymore
			tmpBai.setTemplateBlockNumber(originalBlkNum); // set our template block to it
			BlockAccessIndex bai =
				(BlockAccessIndex) (blockIO.getUsedBlock(tmpBai)); // get  blk from tree in mem cache
			if (bai != null) { // was it in the cache?
				dblk.doClone(bai.getBlk()); // clone it
				bai.getBlk().setIncore(false); // say its no longer loaded in mem cache
			}
			if( Props.DEBUG ) System.out.println("Block #:"+originalBlkNum+" "+dblk);
			blockIO.FseekAndWriteNoLog(originalBlkNum, dblk); // write it back without adding it to undo log
		}
		blockIO.Fforce(); // make sure we synch our main file buffers
		clearIfModified();
		ulog.Fclose();
	}
	public void ResetLog() throws IOException {
		ulog = new FileIO(Name, true);
		clear();
		ulog.Fclose();
	}
	/**
	* Clear the undo log for general collection clear
	* @exception IOException If we can't write the new log header
	*/
	private void clear() throws IOException {
		ulog.Fseek(0L);
		ulog.Fwrite_int(0);
		ulog.Fwrite_long(0L); // update number of keys
		numEntries = 0;
		numKeys = 0;
		if( Props.DEBUG ) System.out.println("UndoLog cleared");
	}
	
	private void clearIfModified() throws IOException {
		if( numEntries > 0 )
			clear();
	}

	/**
	 * Called from dbio before keys file is written
	 * @param numKeys2
	 * @throws IOException 
	 */
	public void updateNumKeys(long numKeys2) throws IOException {
		ulog = new FileIO(Name, true);
		ulog.Fseek(4L);
		ulog.Fwrite_long(numKeys2);
		ulog.Fclose();
		if( Props.DEBUG ) System.out.println("UndoLog numKeys updated");
	}


}
