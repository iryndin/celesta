package ru.curs.celesta.dbschemasync;

import java.io.File;

import ru.curs.celesta.score.AbstractScore;
import ru.curs.celesta.score.Score;
import ru.curs.celesta.score.discovery.ScoreByScorePathDiscovery;

/**
 * Synchronizer class with DBSchema project.
 */
public final class DBSchemaSync {

    private DBSchemaSync() {

    }

    /**
     * The main method.
     *
     * @param args  arguments
     * @throws Exception  if something went wrong
     */
    public static void main(String[] args) throws Exception {
        // "c:/Users/Ivan/workspace/celesta/score/"
        // "c:/Users/Ivan/Desktop/test2.dbs"
        if (args.length < 2) {
            throw new Exception("There should be two arguments: score path and .dbs-file.");
        }

        if (args[0].trim().toLowerCase().endsWith(".dbs")) {
            System.out.println("DBS-->SCORE");
            File dbsFile = new File(args[0]);
            File scoreFile = new File(args[1]);
            System.out.println("parsing score...");
            AbstractScore s = new AbstractScore.ScoreBuilder<>(Score.class)
                    .scoreDiscovery(new ScoreByScorePathDiscovery(args[1]))
                    .build();
            System.out.println("processing...");
            DBSchema2Celesta.dBSToScore(dbsFile, s, scoreFile, args.length > 2 && "-adoc".equalsIgnoreCase(args[2]));

            System.out.println("done.");
        } else {
            File dbsFile = new File(args[1]);
            System.out.println("SCORE-->DBS");
            System.out.println("parsing score...");
            AbstractScore s = new AbstractScore.ScoreBuilder<>(Score.class)
                    .scoreDiscovery(new ScoreByScorePathDiscovery(args[0]))
                    .build();
            System.out.println("processing...");
            Celesta2DBSchema.scoreToDBS(s, dbsFile);
            System.out.println("done.");
        }
    }

}
