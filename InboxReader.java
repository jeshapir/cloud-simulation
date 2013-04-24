package emailstudy;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Properties;
import org.apache.commons.mail.util.MimeMessageParser;

import com.sun.mail.imap.IMAPFolder;

import javax.mail.Address;
import javax.mail.FetchProfile;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.NoSuchProviderException;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.internet.MimeMessage;

/* This is an email scraper I wrote to analyze data from my Brown gmail account 
 * for my experiment in CLPS 700, Social Psychology */
public class InboxReader {

  	// maps email aliases to a hashmap that is a concordance of the email
	private HashMap<String, ArrayList<HashMap<String, Integer>>> _senderToMessageConcordances;
	private HashMap<String, String> _aliasToGender;
	private Session _mailSession;
	private Message _messages[];
	private ArrayList<Message> _messageList;
	private MimeMessageParser _messageParser;
	private HashMap<String, String> _wordToPartOfSpeech;
	HashMap<String, ArrayList<ArrayList<Integer>>> _aliasToMsgConcord;
	float[] _partOfSpeechWeights;
	private static int ALL = -1, NOUN = 0, PLURAL = 1, NOUN_PHRASE = 2, VERB_PARTIC = 3, 
					   VERB_TRANS = 4, VERB_INTRANS = 5, ADJECTIVE = 6, ADVERB = 7,
					   CONJUNC = 8, PREP = 9, INTERJECTION = 10, PRONOUN = 11, DEF_ARTICLE = 12,
					   INDEF_ARTICLE = 13, NOMINATIVE = 14;
	/* if set to ALL, uses all of the parts of speech to analyze the data, otherwise
	 * uses just the given part of speech to peform the analysis */
	private static final int CLASSIFIER = ALL;
	public static final int MIN_MESSAGES = 5;
	private static final int MIN_MSG_LENGTH = 20;
	
	public InboxReader() {
		_senderToMessageConcordances = new HashMap<String, ArrayList<HashMap<String, Integer>>>();
		_wordToPartOfSpeech = new HashMap<String, String>();
		_aliasToGender = new HashMap<String, String>();
		_partOfSpeechWeights = new float[15];
		
		if(CLASSIFIER == ALL) {
			_partOfSpeechWeights[NOUN] = 0.0f;
			_partOfSpeechWeights[PLURAL] = 0.0f;
			_partOfSpeechWeights[NOUN_PHRASE] = 0.0f;
			_partOfSpeechWeights[VERB_PARTIC] = 0.0f;
			_partOfSpeechWeights[VERB_TRANS] = 0.0f;
			_partOfSpeechWeights[VERB_INTRANS] = 0.0f;
			_partOfSpeechWeights[ADJECTIVE] = 0.0f;
			_partOfSpeechWeights[ADVERB] = 0.0f;
			_partOfSpeechWeights[CONJUNC] = 0.25f;
			_partOfSpeechWeights[PREP] = 0.25f;
			_partOfSpeechWeights[INTERJECTION] = 0.0f;
			_partOfSpeechWeights[PRONOUN] = 0.25f;
			_partOfSpeechWeights[DEF_ARTICLE] = 0.25f;
			_partOfSpeechWeights[INDEF_ARTICLE] = 0.0f;
			_partOfSpeechWeights[NOMINATIVE] = 0.0f;
			
		} else {
			
			for(int i = 0; i < _partOfSpeechWeights.length; i++) {
				_partOfSpeechWeights[i] = 0;
			}
			
			_partOfSpeechWeights[CLASSIFIER] = 1;
		}
	}
	
	/* creates a file containing the unique aliases over all of the emails */
	public void constructAliasFile(String inFile, String outFile) {

		try {
			
			System.out.println("Constructing alias file...");
			BufferedReader br = new BufferedReader(new FileReader(inFile));
			BufferedWriter bw = new BufferedWriter(new FileWriter(outFile));
			String currLine = br.readLine();
			HashMap<String, Integer> aliasDict = new HashMap<String, Integer>();
			
			while(currLine != null) {
				
				String[] lineSplit = currLine.split("\\|");
				
				if(!aliasDict.containsKey(lineSplit[0])) {
					aliasDict.put(lineSplit[0], 1);
				}
				
				currLine = br.readLine();
			}
			
			for(String alias : aliasDict.keySet()) {
				bw.write(alias);
				bw.newLine();
				bw.flush();
			}
			
			br.close();
			bw.flush();
			bw.close();
			
		} catch (FileNotFoundException e) {
			System.out.println("File not found with message: " + e.getMessage());
		} catch (IOException e) {
			System.out.println("IOException with message: " + e.getMessage());
		}
	}
	
	public void constructGenderDict(String fileName) {
		
		try {
			
			System.out.println("Constructing gender dictionary...");
			
			BufferedReader br = new BufferedReader(new FileReader(fileName));
			String currLine;
			int numBadEntries = 0;
			
			try {
				currLine = br.readLine();
				
				while(currLine != null) {
					
					String[] aliasAndGender = currLine.split(",");
					
					/* Extract the word and the part of speech. There must be at least 
					 * two elements in aliasAndGender or it's a bad entry */
					if(aliasAndGender.length >= 2) {
						
						String key = aliasAndGender[0]; 
						for(int i = 1; i < aliasAndGender.length - 1; i++) {
							key = key + "," + aliasAndGender[i];
						}
						
						_aliasToGender.put(key, aliasAndGender[aliasAndGender.length - 1].replaceAll("\\s",""));
					} else {
						numBadEntries++;
					}
					
					currLine = br.readLine();
				}
				
				System.out.println("Successfully constructed gender dictionary with " + 
									_aliasToGender.keySet().size() + " entries.");
				System.out.println("Ignored " + numBadEntries + " entries.");
				
			} catch (IOException e) {
				System.out.println("Encountered IOException while constructing dictionary: " + e.getMessage());
				_wordToPartOfSpeech = null;
			}
			
		} catch (FileNotFoundException e) {
			System.out.println("Dictionary file not found.");
			_wordToPartOfSpeech = null;
		}
	}
	
	public void constructPartOfSpeechDict(String fileName) {
		
		System.out.println("Constructing part of speech dictionary...");
		
		try {
			BufferedReader br = new BufferedReader(new FileReader(fileName));
			int numFailedEntries = 0;
			String currLine;
			
			try {
				currLine = br.readLine();
				
				while(currLine != null) {
					
					String[] wordAndPart = currLine.split("\\s+");
					
					/* Extract the word and the part of speech. There must be at least 
					 * two elements in wordAndPart or it's a bad entry */
					if(wordAndPart.length == 2) {
						_wordToPartOfSpeech.put(wordAndPart[0].toLowerCase(), wordAndPart[1].replace("|" , ""));
					} else {
						numFailedEntries++;
					}
					
					currLine = br.readLine();
				}
				
				System.out.println("Successfully constructed part of speech dictionary with " + 
									_wordToPartOfSpeech.keySet().size() + " entries.");
				System.out.println("Ignored " + numFailedEntries + " entries.");
				
			} catch (IOException e) {
				System.out.println("Encountered IOException while constructing dictionary: " + e.getMessage());
				_wordToPartOfSpeech = null;
			}
			
		} catch (FileNotFoundException e) {
			System.out.println("Dictionary file not found.");
			_wordToPartOfSpeech = null;
		}
	}
	
	public void startSession(String email, String password) {
		
		Properties props = System.getProperties();
		props.setProperty("mail.store.protocol", "imaps");
		
			try {
				_mailSession = Session.getDefaultInstance(props, null);
				Store store = _mailSession.getStore("imaps");
				store.connect("imap.gmail.com", email, password);
				System.out.println(store);

				IMAPFolder inbox = (IMAPFolder) store.getFolder("Inbox");
				inbox.open(Folder.READ_ONLY);
				
				System.out.println("Fetching email metadata...");
				_messages = inbox.getMessages();
				FetchProfile fp = new FetchProfile();
				fp.add(FetchProfile.Item.ENVELOPE);
				fp.add(FetchProfile.Item.CONTENT_INFO);
				inbox.fetch(_messages, fp);
				System.out.println("Successfully fetched " + _messages.length + " messages' metadata.");

				System.out.println("Downloading all emails...");
				_messageList = new ArrayList<Message>(_messages.length);
				System.out.println("Search complete.");
				
		} catch (NoSuchProviderException e) {
			System.out.println("Error while starting session: " + e.getMessage());
		} catch (MessagingException e) {
			System.out.println("Error while starting session: " + e.getMessage());
		}
	}
	
	/***
	 * using what is currently stored in _messages, populates the map with the senders
	 */
	public void parseEmails(String emailFile) {
		
		if(_messages != null) {
			
			int numValidEmails = 0;
			String content = "";
			System.out.println("Parsing " + _messages.length + " emails...");
			
			try {
				
				BufferedWriter bw = new BufferedWriter(new FileWriter(emailFile));
				
				for(Message m : _messages) {
					/* construct a concordance for this message */
									
					try {
						
						Address[] addresses = m.getFrom();
						
						boolean addrExists = false;
						
						for(Address addr : addresses) {
							if(_aliasToGender.containsKey(addr.toString())) {
								addrExists = true;
								break;
							}
						}
						
						if(addrExists) {
							if (m.isMimeType("text/plain")) {
								content = (String) m.getContent();
								numValidEmails++;
							} else if(m.isMimeType("multipart/*")) {
								_messageParser = new MimeMessageParser((MimeMessage) m);
								_messageParser.parse();
								content = _messageParser.getPlainContent();
								numValidEmails++;
							} else {
								System.out.println(m.getFrom()[0].toString() + " is another type: " + m.getContentType());
							}
							
							/* clean up the string, i.e. remove punctuation and non-letters */
							content = content.replaceAll("[^\\w\\s]+", "");
							content = content.toLowerCase();
							
							/* Split the string by whitespace */
							String[] wordList = content.split("\\s+");
							int[] partsOfSpeechCount = new int[15];
							String toWrite = addresses[0].toString();
							
							for(String word : wordList) {
								
								if(_wordToPartOfSpeech.containsKey(word.toLowerCase())) {
									String partOfSpeech = _wordToPartOfSpeech.get(word.toLowerCase());
									
									int index = 0;
									
									for(int i = 0; i < partOfSpeech.length(); i++) {
										
										switch(partOfSpeech.charAt(i)) {
											case 'N':
												index = 0;
												break;
											case 'p':
												index = 1;
												break;
											case 'h':
												index = 2;	
												break;
											case 'V':
												index = 3;
												break;
											case 't':
												index = 4;
												break;
											case 'i':
												index = 5;
												break;
											case 'A':
												index = 6;
												break;
											case 'v':
												index = 7;
												break;
											case 'C':
												index = 8;
												break;
											case 'P':
												index = 9;
												break;
											case '!':
												index = 10;
											case 'r':
												index = 11;
												break;
											case 'D':
												index = 12;
												break;
											case 'I':
												index = 13;
												break;
											case 'o':
												index = 14;
												break;
										}
										
										partsOfSpeechCount[index]++;
									}
								}
							}
							
							for(int i = 0; i < partsOfSpeechCount.length; i++) {
								toWrite += "|" + partsOfSpeechCount[i];
							}
							
							toWrite = toWrite + "|" + wordList.length;
							
							bw.write(toWrite);
							bw.newLine();
							bw.flush();
						}
											
					} catch (MessagingException | IOException e) {
						System.out.println("Message/IOException with error: " + e.getMessage());
					} catch(Exception e) {
						System.out.println("MimeMessageParser Exception with error: " + e.getMessage());
					}
				}
				
				bw.flush();
				bw.close();
				
				System.out.println("number of unique addresses: " + _senderToMessageConcordances.keySet().size());
				System.out.println("number of valid emails: " + numValidEmails);
			} catch (IOException e1) {
				System.out.println("Failed to create writer, closing...");
			}	
		}
	}
	
	public void readEmails(String fileName) {
		
		try {
			
			System.out.println("Reading email file...");
			
			HashMap<String, ArrayList<ArrayList<Integer>>> aliasToMsgConcord = new HashMap<String, ArrayList<ArrayList<Integer>>>();
			BufferedReader reader = new BufferedReader(new FileReader(fileName));
			int numEmails = 0;
			String currLine = reader.readLine();
			
			while(currLine != null) {
				
				String[] lineSplit = currLine.split("\\|");
				
				if(! aliasToMsgConcord.containsKey(lineSplit[0])) {
					aliasToMsgConcord.put(lineSplit[0], new ArrayList<ArrayList<Integer>>());
				}
				
				ArrayList<ArrayList<Integer>> msgList = aliasToMsgConcord.get(lineSplit[0]);
				ArrayList<Integer> partsOfSpeech = new ArrayList<Integer>();
				
				for(int i = 1; i < lineSplit.length; i++) {
					try {
						partsOfSpeech.add(Integer.parseInt(lineSplit[i]));
					} catch(NumberFormatException e) {
						partsOfSpeech.add(0);
						System.out.println("Error while parsing integer.");
					}
				}
				
				msgList.add(partsOfSpeech);
				currLine = reader.readLine();
				numEmails++;
			}
			
			reader.close();
			_aliasToMsgConcord = aliasToMsgConcord;
			System.out.println("Successfully read email file with " + _aliasToMsgConcord.size() + " alias entries!");
			System.out.println("Read " + numEmails + " emails.");
			
		} catch (FileNotFoundException e) {
			System.out.println("File not found while trying to read email file.");
		} catch (IOException e) {
			System.out.println("IOException while trying to read email file with message: " + e.getMessage());
		}
	}
	
	public void analyzeData() {
		
		System.out.println("Analyzing emails...");
		HashMap<String, Float> aliasToConsistency = new HashMap<String, Float>();
		int aliasesIgnored = 0;
		
		/* for each person */
		for(String alias : _aliasToMsgConcord.keySet()) {
			
			//System.out.println("alias: " + alias);
			
			float[] averagePartOfSpeech = new float[16];
			
			/* a list of message concordances for the individual */
			ArrayList<ArrayList<Integer>> messageConcords = _aliasToMsgConcord.get(alias);
				
			/* keep a count on the number of ignored messages, so we know how many to leave
			 * out of the average */
			int numIgnoredMsgs = 0;
			
			/* for each array corresponding to the part of speech usage of a particular message */
			for(ArrayList<Integer> msgConcord : messageConcords) {
				
				int numWords = msgConcord.get(msgConcord.size() - 1);
				
				if(numWords >= MIN_MSG_LENGTH) {
				
					/* for each part of speech, add the normalized number of words used in that message */
					for(int i = 0; i < msgConcord.size() - 1; i++) {
						/* add the normalized word count so that each message is weighed equally */
							averagePartOfSpeech[i] += msgConcord.get(i)/(numWords + 0.0001f);
					}
				} else {
					numIgnoredMsgs++;
				}
			}
			
			/* check if there are at least two messages for the individual, otherwise ignore them */
			if((messageConcords.size() - numIgnoredMsgs) >= MIN_MESSAGES) {
			
				/* compute the average word usage for this alias */
				for(int i = 0; i < averagePartOfSpeech.length; i++) {
					averagePartOfSpeech[i]/=(messageConcords.size() - numIgnoredMsgs);
				}
				
				/* for each message, calculate the stylistic different between that message
				 * and the average message */
				
				float LSMScore = 0;
				
				/* for each part of the speech array (a message concordance) of this alias */
				for(ArrayList<Integer> msgConcord : messageConcords) {
					
					int numWords = msgConcord.get(msgConcord.size() - 1);
					
					if(numWords >= MIN_MSG_LENGTH) {
					
						/* for each part of speech count */
						for(int i = 0; i < (msgConcord.size() - 1); i++) {
							
							if((averagePartOfSpeech[i] > 0) || (msgConcord.get(i) > 0)) {
								
								/* normalize this part of speeches contribution to the entire message by dividing it
								 * by the total number of words in the message */
								float speechVal = msgConcord.get(i) / (numWords + 0.0001f);
								
								LSMScore += (1.0 - (Math.abs(averagePartOfSpeech[i] - speechVal)/(averagePartOfSpeech[i]
											+ speechVal + 0.0001))) * _partOfSpeechWeights[i];
							}
						}
					}
				}
				
				LSMScore /= (messageConcords.size() - numIgnoredMsgs);
				System.out.println("alias: " + alias + ", LSMScore: " + LSMScore);
				aliasToConsistency.put(alias, LSMScore);
				
			} else {
				aliasesIgnored++;
			}
		}
		
		float maleScore = 0, femaleScore = 0;
		int numMales = 0, numFemales = 0;
		int cnt = 0;
		
		ArrayList<String> toRemove = new ArrayList<String>();
		
		for(String alias : aliasToConsistency.keySet()) {
			
			if(_aliasToGender.containsKey(alias)) {
				
				/* remove outliers regardless of gender */
				if((aliasToConsistency.get(alias) < 0.98f) && aliasToConsistency.get(alias) > 0.001f) {
				
					if(_aliasToGender.get(alias).equals("m")) {
						
						if((cnt % 2 == 0)) {
							
							maleScore += aliasToConsistency.get(alias);
							numMales++;
						} else {
							toRemove.add(alias);
						}
						
						cnt++;
					} else {
						femaleScore += aliasToConsistency.get(alias);
						numFemales++;
					}
				} else {
					toRemove.add(alias);
					aliasesIgnored++;
				}
			}
		}
		
		for(String alias : toRemove) {
			aliasToConsistency.remove(alias);
		}
		
		System.out.println("Analysis Complete.");
		System.out.println("Males consistency: " + (maleScore/numMales) + " for " + numMales + " individuals.");
		System.out.println("Female consistency: " + (femaleScore/numFemales) + " for " + numFemales + " individuals.");
		System.out.println("Included " + (_aliasToMsgConcord.size() - aliasesIgnored) + " aliases.");
		System.out.println("Ignored " + aliasesIgnored + " aliases.");
		
		this.generateCsv(aliasToConsistency, "src/emailstudy/consistencies.csv");
	}
	
	public void generateCsv(HashMap<String, Float> aliasToConsistency, String fileName) {

		try {
			
			System.out.println("Writing data to csv file...");
			BufferedWriter writer = new BufferedWriter(new FileWriter(fileName));
			
			/* separate the dictionary into two parts: male and female */
			
			ArrayList<Float> maleConsistencies = new ArrayList<Float>();
			ArrayList<Float> femaleConsistencies = new ArrayList<Float>();
			
			for(String alias : aliasToConsistency.keySet()) {
				
				if(_aliasToGender.get(alias).equals("m")) {
					maleConsistencies.add(aliasToConsistency.get(alias));
				} else {
					femaleConsistencies.add(aliasToConsistency.get(alias));
				}
			}
			
			writer.write("male, female");
			writer.newLine();
			
			int length = Math.min(maleConsistencies.size(), femaleConsistencies.size());
			
			for(int i = 0; i < length; i++) {
				writer.write(maleConsistencies.get(i) + "," + femaleConsistencies.get(i));
				writer.newLine();
			}
			
			writer.flush();
			writer.close();
			
			System.out.println("Successfully wrote CSV file!");
			
		} catch (IOException e) {
			System.out.println("Error while trying to write csv file: " + e.getMessage());
		}
	}
	
	public static void main(String args[]) {
		
		InboxReader reader = new InboxReader();
		long timeStart = System.currentTimeMillis();
		reader.constructPartOfSpeechDict("src/emailstudy/part-of-speech.txt");
		reader.constructGenderDict("src/emailstudy/gender-index.txt");
		reader.readEmails("src/emailstudy/emails-final2.txt");
		reader.analyzeData();
		long runTime = (System.currentTimeMillis() - timeStart) / 60000;
		System.out.println("Ran in: " + runTime + " minutes.");
	}

}
