package nl.joosbuijs.bibtex;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;
import java.util.Vector;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Collection;

import org.joda.time.DateTime;

import bibtex.dom.BibtexAbstractEntry;
import bibtex.dom.BibtexEntry;
import bibtex.dom.BibtexFile;
import bibtex.parser.BibtexParser;
import bibtex.parser.ParseException;

/**
 * Class that helps clean/consolidate/fix/improve an existing bibtex file by use
 * of external sources and custom/hard coded fixes.
 * 
 * NOTE AND DISCLAIMER: this code is ugly and was written to only function
 * correctly once on my (messy) thesis bibtex file.
 * 
 * @author jbuijs
 * 
 *         Using https://code.google.com/p/javabib/ as BibTex parser.
 * 
 * 
 *         Using simple DBLP parsers
 *         http://www.informatik.uni-trier.de/~LEY/db/about/simpleparser
 *         /index.html
 * 
 */
public class BibtexCleaner {

    static class EntryComp implements Comparator<BibtexAbstractEntry> {
	public int compare(BibtexAbstractEntry o1, BibtexAbstractEntry o2) {
	    if (o1 instanceof BibtexEntry && o2 instanceof BibtexEntry) {
		return ((BibtexEntry)o1).getEntryKey().toLowerCase().compareTo(((BibtexEntry)o2).getEntryKey().toLowerCase());
	    } else if (o1 instanceof BibtexEntry) {
		return -1;
	    } else if (o2 instanceof BibtexEntry) {
		return 1;
	    } else {
		return 0;
	    }
	}
	public boolean equals(BibtexAbstractEntry o1, BibtexAbstractEntry o2) {
	    if (o1 instanceof BibtexEntry && o2 instanceof BibtexEntry) {
		return ((BibtexEntry)o1).getEntryKey().equals(((BibtexEntry)o2).getEntryKey());
	    } else if (o1 instanceof BibtexEntry) {
		return false;
	    } else if (o2 instanceof BibtexEntry) {
		return false;
	    } else {
		return true;
	    }
	}
    }

    public static void mergeEntries(BibtexEntry entry, BibtexEntry newEntry) {
	for (String fieldKey : newEntry.getFields().keySet()) {
	    if (!entry.getFields().containsKey(fieldKey)) {
		entry.addFieldValue(fieldKey, newEntry.getFieldValue(fieldKey));
	    }
	}
	if (!entry.getEntryKey().toLowerCase().equals(newEntry.getEntryKey().toLowerCase())) {
	    entry.addFieldValue("AlternateName" + entry.getFields().size(), newbibtex.makeString(newEntry.getEntryKey()));
	}
    }


    public static BibtexEntry mergeDBLPEntry(BibtexEntry entry, BibtexEntry newEntry) {
	mergeEntries(newEntry, entry);
	newEntry.addFieldValue(DBLPKey, newbibtex.makeString(newEntry.getEntryKey()));
	newEntry.setEntryKey(entry.getEntryKey());
	return newEntry;
    }

    public static void addEntry(BibtexEntry entry) {
	if (knownEntries.get(entry.getEntryKey().toLowerCase()) != null) {
	    mergeEntries(knownEntries.get(entry.getEntryKey().toLowerCase()), entry);
	    entry = knownEntries.get(entry.getEntryKey().toLowerCase());
	}
	if (entry.getFields().containsKey(DBLPKey)
	    && knownEntries.get(entry.getFieldValue(DBLPKey).toString().toLowerCase()) != null) {
	    knownEntries.put(entry.getEntryKey().toLowerCase(), entry);
	    mergeEntries(knownEntries.get(entry.getFieldValue(DBLPKey).toString().toLowerCase()), entry);
	} else {
	    knownEntries.put(entry.getEntryKey().toLowerCase(), entry);
	}
    }

    public static BibtexEntry[] cleanEntry(BibtexEntry entry, DBLPQueryParser dblpQuery) {
	System.out.println("Cleaning entry " + entry.getEntryKey());
	try {
	    Pair<BibtexEntry, BibtexEntry> firstMatch = dblpQuery.getFirstEntryForQuery(entry.getFieldValue("title").toString());
	    System.out.println(" Found " + dblpQuery.getNrOfResults() + " results on DBLP");
	    if (dblpQuery.getNrOfResults() > 1) {
		System.out.println("Fine-tuning...");
		firstMatch = dblpQuery.getFirstEntryForQuery(entry.getFieldValue("title").toString() + " " + BibtexCleaner.getAuthorNames(entry));
		if (dblpQuery.getNrOfResults() == 0) {
		    firstMatch = dblpQuery.getFirstEntryForQuery(entry.getFieldValue("title").toString());
		}
	    }

	    if (dblpQuery.getNrOfResults() > 10 || dblpQuery.getNrOfResults() < 1) {
		System.out.println(dblpQuery.getNrOfResults() + " results, using old entry...");
		return new BibtexEntry[] { entry };
	    } else if (dblpQuery.getNrOfResults() > 1) {
		System.out.println("We got too many results, remembering and letting user choose best option later.");
		BibtexEntry[] r = new BibtexEntry[dblpQuery.getNrOfResults() + 1];
		r[0] = entry;
		for (int i = 0; i < dblpQuery.getNrOfResults(); i++) {
		    r[i + 1] = dblpQuery.getEntryByIndex(i).getFirst();
		}
		return r;
	    } else {
		return new BibtexEntry[] { mergeDBLPEntry(entry, firstMatch.getFirst()) };
	    }
	} catch(Exception e) {
	    // just keep the entry and keep going
	    System.out.println("Exception, uhhh");
	    return new BibtexEntry[] { entry };
	}
    }

    public static final String DBLPKey = "DBLPkey";
    public static HashMap<String, BibtexEntry> knownEntries = new HashMap<String, BibtexEntry>();
    public static int AUTHORS_IN_QUERY_MAX_ELEMENTS = 7; //manifesto causes a HTTP 414 error: URL too long :D

    public static void SaveFiles(String sourceBibFile) throws FileNotFoundException {
	String newBibFile = sourceBibFile.replace(".bib", "_cleaned.bib");
	newbibtex.printBibtex(new PrintWriter(new File(newBibFile)));
    }

    public static List<BibtexEntry> prepareEntryList(String sourceBibFile, String f) throws ParseException, FileNotFoundException, IOException {
	// Load file
	BibtexFile file = new BibtexFile();
	(new BibtexParser(false)).parse(file, new FileReader(sourceBibFile));
	System.out.println("loaded dirty Bibtex file " + sourceBibFile);

	// load good entry list
	BibtexFile rfile = new BibtexFile();
	Set<String> goodEntries = new HashSet<String>();
	(new BibtexParser(false)).parse(rfile, new FileReader(f));
	goodEntries.clear();
	for (BibtexAbstractEntry potentialEntry : rfile.getEntries()) {
	    if (potentialEntry instanceof BibtexEntry) {
		goodEntries.add(((BibtexEntry) potentialEntry).getEntryKey().toLowerCase());
	    }
	}
	System.out.println("loaded filter Bibtex file " + f);

	// merge duplicates and filter out
	HashMap<String, BibtexEntry> subEntries = new HashMap<String, BibtexEntry>();
	for (BibtexAbstractEntry potentialEntry : file.getEntries()) {
	    if (potentialEntry instanceof BibtexEntry) {
		String k = ((BibtexEntry)potentialEntry).getEntryKey().toLowerCase();
		if (goodEntries.contains(k)) {
		    if (subEntries.get(k) != null) {
			mergeEntries(subEntries.get(k), ((BibtexEntry)potentialEntry));
		    } else {
			subEntries.put(k, (BibtexEntry)potentialEntry);
		    }
		}
	    }
	}

	// Sort by key
	return sortedEntries(subEntries.values());
    }

    public static List<BibtexEntry> sortedEntries(Collection<BibtexEntry> c) {
	ArrayList<BibtexEntry> entries = new ArrayList<BibtexEntry>();
	entries.addAll(c);
	Collections.sort(entries, new EntryComp());
	return entries;
    }

    static BibtexFile newbibtex = new BibtexFile();

    public static void main(String... strings) {
	if (strings.length < 1 || strings.length > 2) {
	    System.err.println("\nMust pass input bibtex file as first argument, and optionally a restricted file as second");
	    return;
	}

	try {
	    String sourceBibFile = strings[0];	    
	    String toplevelComment = String.format("%% This bibTex file was cleaned by BibtexCleaner by Joos Buijs %n"
						   + "%% Cleaned version of %s%n" + "%% Cleaned on %s%n" + "%% %n%n", sourceBibFile, DateTime.now());
	    newbibtex.addEntry(newbibtex.makeToplevelComment(toplevelComment));
	    List<BibtexEntry> entries = prepareEntryList(strings[0], strings.length == 2 ? strings[1] : strings[0]);

	    DBLPQueryParser dblpQuery = new DBLPQueryParser();
	    Vector<BibtexEntry[]> rememberedEntries = new Vector<BibtexEntry[]>();
	    for (BibtexEntry potentialEntry : entries) {
		BibtexEntry[] results = cleanEntry(potentialEntry, dblpQuery);
		if (results.length == 1) {
		    addEntry(results[0]);
		} else {
		    rememberedEntries.add(results);
		}
	    }

	    for (int e = 0; e < rememberedEntries.size(); e++) {
		BibtexEntry[] rlist = rememberedEntries.elementAt(e);
		System.out.println(" We got too many results, please choose the best option.");
		System.out.println(" Original: ");
		System.out.println(BibtexCleaner.bibtexEntryToString(rlist[0]));
		for (int i = 1; i < rlist.length; i++) {
		    System.out.println(" SUGGESTION " + i + ":");
		    System.out.println(BibtexCleaner.bibtexEntryToString(rlist[i]));
		}
		System.out.println(" Which one should we use? Press ENTER to use old entry...");
		Scanner sysoIn = new Scanner(System.in);
		String answer = sysoIn.nextLine();
		if (answer.isEmpty()) {
		    addEntry(rlist[0]);
		} else {
		    int correctIndex = Integer.parseInt(answer);
		    System.out.println(" Using entry at index " + correctIndex);
		    addEntry(mergeDBLPEntry(rlist[0], rlist[correctIndex]));
		}
	    }

	    for (BibtexEntry e : sortedEntries(knownEntries.values())) {
		newbibtex.addEntry(e);
	    }
	    SaveFiles(sourceBibFile);
	    System.out.println("DONE");
	} catch (ParseException e) {
	    e.printStackTrace();
	} catch (FileNotFoundException e) {
	    // TODO Auto-generated catch block
	    e.printStackTrace();
	} catch (IOException e) {
	    // TODO Auto-generated catch block
	    e.printStackTrace();
	}
    }

    /**
     * Returns the author names, without initials, from the given entry
     * 
     * @param entry
     * @return
     */
    public static String getAuthorNames(BibtexEntry entry) {
	String result = "";
		
	if(entry.getFieldValue("author")==null){
	    return result;
	}
		
	String[] chunks = entry.getFieldValue("author").toString().split(" |,");
	int nrAuthorChunks = 0;
	for (String chunk : chunks) {
	    if (chunk.length() > 2 && !chunk.equals("and")) {
		result += " " + chunk;
	    }
	    nrAuthorChunks++;
	    if (nrAuthorChunks >= AUTHORS_IN_QUERY_MAX_ELEMENTS) {
		break;
	    }
	}
	return result;
    }

    public static String bibtexEntryToString(BibtexEntry entry) {
	if (entry == null) {
	    return "NULL";
	}
	Writer pwout = new StringWriter();
	PrintWriter pw = new PrintWriter(pwout);
	entry.printBibtex(pw);
	return pwout.toString();
    }

    public static boolean isCrossrefType(BibtexEntry entry) {
	return entry.getEntryType().equalsIgnoreCase("proceedings")
	    || entry.getEntryType().equalsIgnoreCase("collection");
    }
}
