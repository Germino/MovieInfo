package net.pms.movieinfo;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.lang.StringUtils;
import org.h2.engine.Constants;
import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.jdbcx.JdbcDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jna.Platform;

import net.pms.PMS;
import net.pms.dlna.DLNAResource;
import net.pms.dlna.RealFile;
import net.pms.dlna.virtual.VirtualFolder;
import net.pms.formats.Format;
import net.pms.formats.FormatFactory;

public class MovieDB extends VirtualFolder {
	private Thread scanner;
	private int dbCount;
	private String scanPath;
	private static final Logger logger = LoggerFactory.getLogger(MovieDB.class);
	
	private static String[] KeyWords = { "Genre", "Title", "Director", 
										 "Rating", "AgeRating"};
	
	public MovieDB() {
		super("MovieDB", null);
		setupTables();
	}
	
	public static JdbcConnectionPool getDBConnection() {
		/* This is take from DLNAMediaDb*/
		String url;
		String dbName;
		String dir = "database";
		dbName = "movieDB";
		File fileDir = new File(dir);
		if (Platform.isWindows()) {
			String profileDir = PMS.getConfiguration().getProfileDirectory();
			url = String.format("jdbc:h2:%s\\%s/%s", profileDir, dir, dbName);
			fileDir = new File(profileDir, dir);
		} else {
			url = Constants.START_URL + dir + "/" + dbName;
		}
		JdbcDataSource ds = new JdbcDataSource();
		ds.setURL(url);
		ds.setUser("sa");
		ds.setPassword("");
		return JdbcConnectionPool.create(ds);
	}
	
	private void executeUpdate(Connection conn, String sql) throws SQLException {
		if (conn != null) {
			Statement stmt = conn.createStatement();
			stmt.executeUpdate(sql);
		}
	}
	
	private static void close(Connection conn) {
		try {
			if (conn != null) {
				conn.close();
			}
		} catch (SQLException e) {
			PMS.info("close error "+e);
		}
	}
	
	private void setupTables() {
		JdbcConnectionPool cp=getDBConnection();
		Connection conn =null;
		ResultSet rs = null;
		Statement stmt = null;
		try {
			conn = cp.getConnection();
			stmt = conn.createStatement();
			rs = stmt.executeQuery("SELECT count(*) FROM FILES");
			if (rs.next()) {
				dbCount = rs.getInt(1);
			}
			rs.close();
			stmt.close();
			if(dbCount != -1) { 
				/* tables exist, just return */
				//dumpTable();
				close(conn);
				return;
			}
		} catch (Exception e) {
			PMS.info("setup tabe error "+e);
		} finally {
			close(conn);
		}
		try {
			conn = cp.getConnection();
			StringBuilder sb = new StringBuilder();
			sb.append("CREATE TABLE FILES (");
			sb.append(" ID                INT AUTO_INCREMENT");
			sb.append(", FILENAME          VARCHAR2(1024)  NOT NULL");
			sb.append(", IMDB          VARCHAR2(1024)       NOT NULL");
			sb.append(", OSHASH          VARCHAR2(1024)       NOT NULL");
			sb.append(", THUMB         VARCHAR2(1024)");
			sb.append(", TAGLINE         VARCHAR2(1024)");
			sb.append(", PLOT         VARCHAR2(2048)");			
			for(String word : KeyWords) {
				sb.append(", "+word+"                VARCHAR2(1024)");
			}
			sb.append(", primary key (ID))");
			executeUpdate(conn, sb.toString());
			sb=new StringBuilder();
			sb.append("CREATE TABLE CAST (");
			sb.append(" ID                INT AUTO_INCREMENT");
			sb.append(", CAST  VARCHAR2(1024)  NOT NULL");
			sb.append(", MOVIE INT");
			sb.append(", THUMB VARCHAR2(1024)");
			sb.append(", CHAR VARCHAR2(1024)");
			sb.append(", primary key (ID))");
			executeUpdate(conn, sb.toString());
		} catch (SQLException se) {
			PMS.info("create mi tb error "+se);
		} finally {
			close(conn);
		}
	}
	
	private boolean dispFilter(String str) {
		String[] disp = MovieInfo.cfg().getDisplay();
		if(disp==null || disp.length==0)
			return false;
		for(int i=0;i<disp.length;i++)
			if(str.equalsIgnoreCase(disp[i]))
				return false;
		return true;
	}
	
	public void discoverChildren() { 	
		for(String word : KeyWords) {
			if(dispFilter(word))
				continue;
			String sql="SELECT "+word+" from FILES";
			if(word.equalsIgnoreCase("rating"))
				sql=sql+" order by rating desc";
			MovieDBFolder m =new MovieDBFolder(word,sql);
			addChild(m);
		}
		// we add the cast separatly...
		MovieDBFolder m = new MovieDBFolder("Cast","SELECT CAST,THUMB from CAST");
		m.cast();
		addChild(m);
	}
	
	private static String ucFirst(String str) {
		char first=str.charAt(0);
		return String.valueOf(first).toUpperCase()+str.substring(1);
	}
	
	private static String fixStr(String s) {
		if(StringUtils.isEmpty(s))
			return "";
		return ucFirst(s.trim());
	}
	
	private static String fixRating(String r) {
		int pos=r.indexOf("/");
			r=r.substring(0,pos).trim();
		return fixStr(r);
	}
	
	public static void add(DLNAResource res,String imdb,String genres,
			   String title,String rating,
			   String director,String agerating,
			   ArrayList<String> cast,String thumb,
			   String hash,String plot,String tag) {
		if(res==null)
			return;
		if(res instanceof RealFile) {
			String file=((RealFile)res).getFile().getAbsolutePath();
			add(file,imdb,genres,title,rating,director,agerating,cast,
					thumb,hash,plot,tag);
		}
	}
	
	
	public static void add(String file,String imdb,String genres,
						   String title,String rating,
						   String director,String agerating,
						   ArrayList<String> cast,String thumb,
						   String hash,String plot,String tag) {
		if(!MovieInfo.movieDB())
			return;
		Connection conn = null;
		PreparedStatement ps = null;
		PreparedStatement ps1 = null;
		ResultSet rs = null;
		JdbcConnectionPool cp=getDBConnection();
		int id=0;
		try {
			conn = cp.getConnection();
			ps = conn.prepareStatement("INSERT INTO FILES(FILENAME, IMDB, OSHASH,GENRE, TITLE, DIRECTOR, RATING, AGERATING, THUMB,PLOT,TAGLINE) VALUES (?,?,?,?, ?, ?, ?, ?, ?, ?,?)",
									   Statement.RETURN_GENERATED_KEYS);
			ps1 = conn.prepareStatement("INSERT INTO CAST(CAST, MOVIE, THUMB,CHAR) VALUES (?,?,?,?)");
			String[] tmp=genres.split(",|/");
			for(int i=0;i<tmp.length;i++) {
				ps.setString(1, file);
				ps.setString(2, imdb);
				ps.setString(3, fixStr(hash));
				ps.setString(4, fixStr(tmp[i]));
				ps.setString(5, fixStr(title));
				ps.setString(6, fixStr(director));
				ps.setString(7, fixRating(rating));
				ps.setString(8, fixStr(agerating));
				ps.setString(9, thumb);
				ps.setString(10, fixStr(plot));
				ps.setString(11, fixStr(tag));
				ps.executeUpdate();
				rs=ps.getGeneratedKeys();
				rs.next();
				if(id==0)
					id=rs.getInt(1);
			}
			// Build Cast array
			while(!cast.isEmpty()) {
				String t=cast.remove(0);
				String name=cast.remove(0);
				String c=cast.remove(0);
				ps1.setString(1, name);
				ps1.setInt(2, id);
				ps1.setString(3, t);
				ps1.setString(4, c);
				ps1.executeUpdate();
			}
		} catch (Exception e) {
			PMS.info("insert into mdb "+e);
		} finally {
			try {
				if(ps!=null)
					ps.close();
				if(ps1!=null)
					ps1.close();
				if(rs!=null)
					rs.close();
			} catch (SQLException e) {
				PMS.info("error insert");
			}
			close(conn);
		}
	}
	
	public MovieDBPlugin findInDB(String name) {
		Connection conn = null;
		PreparedStatement ps = null;
		PreparedStatement ps1 = null;
		ResultSet rs = null;
		JdbcConnectionPool cp=getDBConnection();
		MovieDBPlugin p = null;
		try {
			conn = cp.getConnection();
			ps=conn.prepareStatement("SELECT * FROM FILES WHERE upper(FILENAME) = ?");
			ps.setString(1, name.toUpperCase());
			rs=ps.executeQuery();
			ArrayList<Integer> castId = new ArrayList<Integer>();
			while(rs.next()) {
				if(p==null)
					p=new MovieDBPlugin(rs);
				p.addGenre(rs.getString("GENRE"));
				castId.add(rs.getInt("ID"));
			}
			if(p!=null) {
				rs.close();
				for(int id : castId) {
					ps1 = conn.prepareStatement("SELECT * FROM CAST WHERE MOVIE = ?");
					ps1.setInt(1, id);
					rs=ps1.executeQuery();
					while(rs.next()) {
						p.addCast(rs);
					}
					rs.close();
				}
			}
		} catch (Exception e) {
			logger.debug("got exception in findindb "+e);
		}
		finally {
			try {
				if(ps!=null)
					ps.close();
				if(ps1!=null)
					ps1.close();
				if(rs!=null)
					rs.close();
			} catch (SQLException e) {
				PMS.info("error insert");
			}
		}
		close(conn);
		return p;
	}

	public synchronized void stopScanLibrary() {
		if (scanner != null && scanner.isAlive()) {
			
		}
	}
	
	public synchronized boolean isScanLibraryRunning() {
		return scanner != null && scanner.isAlive();
	}
	
	public synchronized void scanLibrary(String path) {
		if(!MovieInfo.movieDB())
			return;
		scanPath=path;
		PMS.getConfiguration().setCustomProperty("movieinfo.scan_path", scanPath);
		try {
			PMS.getConfiguration().save();
		} catch (ConfigurationException e) {
		}
		Runnable r = new Runnable() {
			public void run() {
				File[] dirs;
				if(scanner==null) // weird
					return;
				if(StringUtils.isEmpty(scanPath))
					dirs=PMS.get().getFoldersConf();
				else {
					String[] foldersArray = scanPath.split(",");
					dirs=new File[foldersArray.length];
					for(int i=0;i<foldersArray.length;i++) {
						dirs[i]=new File(foldersArray[i]);
					}
				}
				for(File f : dirs) {
					PMS.info("scan dir "+f.getAbsolutePath());
					if(!f.exists())
						continue;
					scanDir(f,new ArrayList<File>());
				}
			}
		};
		if (scanner == null) {
			scanner = new Thread(r, "Library Scanner");
			scanner.start();
		} else if (scanner.isAlive()) {
		} else {
			scanner = new Thread(r, "Library Scanner");
			scanner.start();
		}
	}
	
	private boolean alreadyScanned(File f) {
		Connection conn = null;
		PreparedStatement ps = null;
		ResultSet rs = null;
		boolean r=false;
		JdbcConnectionPool cp=getDBConnection();
		try {
			conn = cp.getConnection();
			ps = conn.prepareStatement("SELECT FILENAME from FILES WHERE FILENAME = ?");
			ps.setString(1, f.getAbsolutePath());
			rs=ps.executeQuery();
			if(rs.next())
				r= true;
		} catch (Exception e) {
			logger.debug("error in alread scaned "+e);
		} finally {
			try {
				if(ps!=null)
					ps.close();
				if(rs!=null)
					rs.close();
			} catch (SQLException e) {
			}
		}
		close(conn);	
		return r;
	}
	
	private boolean recurse() {
			String s=(String)PMS.getConfiguration().getCustomProperty("movieinfo.movieDB_recurse");
			if(StringUtils.isNotEmpty(s))
				return s.equalsIgnoreCase("true");
			return false;
	}
	
	private void scanDir(File dir,ArrayList<File> scanned) {
		File[] files=dir.listFiles();
		for(File f : files) {
			try {
				if(f.isHidden())
					continue;
				if(f.isDirectory() && recurse()) {
					if(!scanned.contains(f)) { 
						// don't scan dirs more than once
						scanned.add(f);
						scanDir(f,scanned);
					}
					continue;
				}
				Format form = FormatFactory.getAssociatedExtension(f.getAbsolutePath());
				if(form==null || !form.isVideo()) {
					// skip this crap
					continue;
				}
				
				if(alreadyScanned(f)) {
					continue;
				}
				String hash=OpenSubs.getHash(f);
				if(StringUtils.isEmpty(hash))
					continue;
				String imdb=OpenSubs.fetchImdbId(hash);
				if(StringUtils.isEmpty(imdb)) {
					PMS.info("couldn't fetch imdbid "+f);
					continue;
				}
				if(!imdb.startsWith("tt"))
					imdb="tt"+imdb;
				FileMovieInfoVirtualFolder fmf =new FileMovieInfoVirtualFolder(
													"IMDB INFO",
													null,
													0,0,imdb,new RealFile(f));
				fmf.setHash(hash);
				fmf.gather();
				if(MovieInfo.cfg().getCover().equals("1")) {
					File cFile = new File(f.getAbsolutePath()+".cover.jpg");
					fmf.saveCover(cFile);
				}
			} catch (IOException e) {
			}
		}
	}
	
	private void dumpTable() {
		Connection conn = null;
		Statement stmt = null;
		ResultSet rs = null;
		JdbcConnectionPool cp=getDBConnection();
		try {
			conn = cp.getConnection();
			stmt=conn.createStatement();
			rs = stmt.executeQuery("SELECT * FROM FILES");
			logger.debug("MovieDB:");
			while(rs.next()) {
				logger.debug("file "+rs.getString("FILENAME"));
				logger.debug("title "+rs.getString("TITLE"));
				logger.debug("rating "+ rs.getString("RATING"));
				logger.debug("agerating "+ rs.getString("AGERATING"));
				logger.debug("thumb "+ rs.getString("THUMB"));
				logger.debug("dir "+ rs.getString("DIRECTOR"));
				logger.debug("id "+ rs.getInt("ID"));
				logger.debug("tagline "+ rs.getString("TAGLINE"));
				logger.debug("plot "+ rs.getString("PLOT"));
			}
		} catch (Exception e) {
			PMS.info("error in dump "+e);
		} finally {
			try {
				if(stmt!=null)
					stmt.close();
				if(rs!=null)
					rs.close();
			} catch (SQLException e) {
			}
		}
		close(conn);
	}
	
	public static boolean movieDBParent(DLNAResource start) {
		DLNAResource tmp = start;
		while(tmp!=null) {
			if(tmp instanceof MovieDB)
				return true;
			tmp=tmp.getParent();
		}
		return false;
	}
}
