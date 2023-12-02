package sim;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseWheelEvent;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

// TODO reminder for blog post throughout

/* TODO:
 * - have trains travel along ComplexLines
 * - implement citizen class (waiting for trains, waiting at stops, moving between stops, travelling with trains, graphical representation, etc.)
 * - click-to-spawn citizens + random / proportional citizen generation based on density maps (+ time-of-day?)
 * - better logging (Logger class with verbosity levels?)
 * - better documentation
 * - background geography ??
 * - time-based pathfinding (not just distance-based, but using train arrival times) ??
 * - speed up/slow down simulation ??
 * - simulation statistics (+ graphing ??) ??
 * - zoop to mouse (work out math??) & scaling panning to zoom
 * - map rotation ??
 */

/* TODO procedure for implementing pathfinding:
 * - use a HashMap to have each node store nearest nodes on line as neighbors w/ dists
 * 	- (at least eventually) this may require/recommend reworking the distance system. 
 * 	  	maybe instead of the current system, each node stores neighbors, and lines are just records of nodes that use neighbor hashmaps to get dists for trains??
 * - have each node also store nearest nodes by distance as neighbors (for transfers)
 * 	- requires parameter K_NEAREST and a way to generate this efficiently (ideally not O(n^2))
 * 		initial impulse for this is to just iterate over all nodes, get the smallest 5 by distance (this is the O(n^2) implementation)
 * 		can also segment the entire grid first and make subarrays?? but then it still might be slow.. so maybe do the first and then write notes about the second
 * - then implement a pretty basic pathfinding algorithm to get from node to node
 * - then implement waiting and pickup/offload mechanics for trains/citizens
 */

public class Simulator {

	public static void main(String [] args) {

		// app initialization
		App app = new Sim(473, 3.0, new Vector3(150, 180, 30), new Vector2(0.9, 1), new Vector2(64, 64), Vector3.white, 1024);

		app.run();

	}

}

class Sim extends App {

	private Line[] lines;
	private Node[] nodes;
	private ArrayList<ArrayList<ArrayList<Node>>> segmentedNodes;
	private ComplexLine[] complexLines;
	private int numStops;
	private int nodeSegmentSize;

	private double globalTime;
	private double timeIncrement;

	private double MAP_X_SCALE;
	private double MAP_Y_SCALE;

	private Vector2 mouseInitialPos;

	public Sim(int numStops, double timeIncrement, Vector3 WORLD_SIZE, Vector2 MAP_X_Y_SCALE, Vector2 windowTopLeft, Vector3 backgroundColor, int windowHeight) {

		assert WORLD_SIZE.x % WORLD_SIZE.z <= 0.0001 && WORLD_SIZE.y % WORLD_SIZE.z <= 0.0001;
		
		this.numStops = numStops;
		this.timeIncrement = timeIncrement;
		setWindowSizeInWorldUnits(WORLD_SIZE.x, WORLD_SIZE.y);
		this.nodeSegmentSize = (int) WORLD_SIZE.z;
		this.MAP_X_SCALE = MAP_X_Y_SCALE.x * 0.5;
		this.MAP_Y_SCALE = MAP_X_Y_SCALE.y * 0.5;
		setWindowTopLeftCornerInPixels((int) windowTopLeft.x, (int) windowTopLeft.y);
		setWindowCenterInWorldUnits(0.0, 0.0);
		setWindowHeightInPixels(windowHeight);
		setWindowBackgroundColor(backgroundColor);

	}

	void setup() {

		// mouse wheel zooming
		this.addMouseWheelListener( new MouseAdapter() {
			@Override public void mouseWheelMoved(MouseWheelEvent e) {

				Drawable.adjustZoom(-e.getWheelRotation() * Drawable.ZOOM_CONST);

			}
		});

		// initialize zooming and panning variables
		mouseInitialPos = new Vector2(0, 0);
		Drawable.resetPanZoom();

		// iterate through stations and add stops to appropriate lines
		nodes = new Node[numStops];
		double[] stationX = new double[numStops];
		double[] stationY = new double[numStops];
		int c = 0;

		HashMap<String, Line> lines = new HashMap<String, Line>();

		try (BufferedReader reader = new BufferedReader(new FileReader("src/sim/stations_data.csv")) ) {

			String line;

			while ((line = reader.readLine()) != null) {

				String[] n = line.split(",");
				String[] stopLines = n[4].split("-");

				for (String str : stopLines) {

					if (!lines.containsKey(str)) {

						lines.put(str, new Line(str));

					}

				}

				Node stop = new Node(n[1], new Vector2(), Vector3.black);
				nodes[c] = stop;
				stationX[c] = Double.parseDouble(n[2]);
				stationY[c] = Double.parseDouble(n[3]);
				c++;

				for (String str : stopLines) {

					lines.get(str).addStop(stop, 1);

				}

			}

		} catch (IOException e) { assert false; }

		// convert real-world geometry data to world units
		Vector2 xMinMax = getMinMax(stationX);
		Vector2 yMinMax = getMinMax(stationY);
		normalize(stationX, -this._windowWidthInWorldUnits * MAP_X_SCALE, this._windowWidthInWorldUnits * MAP_X_SCALE, xMinMax.x, xMinMax.y);
		normalize(stationY, -this._windowHeightInWorldUnits * MAP_Y_SCALE, this._windowHeightInWorldUnits * MAP_Y_SCALE, yMinMax.x, yMinMax.y);

		for (int i = 0; i < stationX.length; i++) {

			nodes[i].setPos(stationX[i], stationY[i]);

		}
		
		segmentedNodes = new ArrayList<ArrayList<ArrayList<Node>>>();
		
		for (int i = 0; i < (int) this._windowWidthInWorldUnits / nodeSegmentSize + 3; i++) {
			
			segmentedNodes.add(new ArrayList<ArrayList<Node>>());
			for (int j = 0; j < (int) this._windowHeightInWorldUnits / nodeSegmentSize + 3; j++) {
				
				segmentedNodes.get(i).add(new ArrayList<Node>());
				
			}
			
		}
				
		for (Node n : nodes) { 
						
			int xIndex = ((int) (this._windowWidthInWorldUnits/2 + n.getPos().x)/nodeSegmentSize) + 1;
			int yIndex = ((int) (this._windowHeightInWorldUnits/2 + n.getPos().y)/nodeSegmentSize) + 1;
			
			if (xIndex >= 6|| yIndex >= 7) {
				
				System.out.println(n);
				
			}
			
			segmentedNodes.get(xIndex).get(yIndex).add(n);
			n.setSegmentIndex(xIndex, yIndex);
			
		}
		
		for (Node n : nodes) {
			
			for (int i = -1; i <= 1; i++) {
				
				for (int j = -1; j <= 1; j++) {
					
					for (Node n2 : segmentedNodes.get(n.getXSegmentIndex()+i).get(n.getYSegmentIndex()+j)) {
						
						double dist = Vector2.distanceBetween(n.getPos(), n2.getPos());
						if (dist <= Node.DEFAULT_TRANSFER_MAX_DIST) {
							
							Node.addNeighborPair(n, n2, dist);
							
						}
						
					}
					
				}
				
			}
			
		}

		// load in configurations for proper stop orders for lines
		HashMap<String, String> lineConfigs = new HashMap<String, String>();
		try (BufferedReader reader = new BufferedReader(new FileReader("src/sim/lines_stations.csv")) ) {

			String line;
			while ((line = reader.readLine()) != null) {

				if (line.indexOf(",") == -1) { continue; }
				String lineID = line.substring(0, line.indexOf(","));
				lines.get(lineID).setColor(new Vector3Mod(line.substring(line.indexOf(",") + 1, line.indexOf(",", line.indexOf(",") + 1))));
				lineConfigs.put(line.substring(0, line.indexOf(",")), line.substring(line.indexOf(",", line.indexOf(",") + 1) + 1));

			}

		} catch (IOException e) { assert false; }
		
		// apply line configurations, remove problematic/invalid lines
		ArrayList<String> linesToRemove = new ArrayList<String>();

		for (Line l : lines.values()) {

			for (int i = 0; i < l.getLength(); i += 8) {

				l.addTrain(new Train(i, l, l.getColor(), timeIncrement * Train.DEFAUlT_TRAIN_SPEED));

			}

			try {

				l.rearrangeStops(lineConfigs.get(l.getID()).split(","));
				l.overrideStopColors();

			} catch (Exception e) {

				System.out.println("Deleted invalid line " + l.getID());
				linesToRemove.add(l.getID());

			}

		}

		for (String s : linesToRemove) {

			lines.remove(s);

		}

		System.out.println("Generated lines " + lines.keySet());

		// add lines to simulation array
		c = 0;
		this.lines = new Line[lines.keySet().size()];

		for (Line l : lines.values()) {

			this.lines[c++] = l;

		}

		// generate complex lines for drawing
		ArrayList<ComplexLine> complexLinesBuilder = new ArrayList<ComplexLine>();
		try(BufferedReader reader = new BufferedReader(new FileReader("src/sim/lines_geom_data.csv")) ) {

			String line;

			while ((line = reader.readLine()) != null) {

				String[] lineData = line.split(",");
				Vector3 lineColor = lines.get(lineData[4]).getColor();
				String[] lineX = lineData[6].split(" ");
				String[] lineY = lineData[7].split(" ");
				double[] lineXD = new double[lineX.length];
				double[] lineYD = new double[lineY.length];
				Node[] lineNodes = new Node[lineX.length];

				for (int i = 0; i < lineX.length; i++) {

					lineXD[i] = Double.parseDouble(lineX[i]);
					lineYD[i] = Double.parseDouble(lineY[i]);

				}

				normalize(lineXD, -this._windowWidthInWorldUnits * MAP_X_SCALE, this._windowWidthInWorldUnits * MAP_X_SCALE, xMinMax.x, xMinMax.y);
				normalize(lineYD, -this._windowHeightInWorldUnits * MAP_Y_SCALE, this._windowHeightInWorldUnits * MAP_Y_SCALE, yMinMax.x, yMinMax.y);

				for (int i = 0; i < lineXD.length; i++) {

					lineNodes[i] = new Node(new Vector2(lineXD[i], lineYD[i]), lineColor);

				}

				complexLinesBuilder.add(new ComplexLine(lineData[1], lineColor, lineNodes, Double.parseDouble(lineData[5])));

			}

		} catch (IOException e) { assert false; }

		complexLines = new ComplexLine[complexLinesBuilder.size()];
		for (int i = 0; i < complexLines.length; i++) {

			complexLines[i] = complexLinesBuilder.get(i);

		}
		
		System.out.println(nodes[102] + " " + nodes[304]);
		System.out.println(Node.findPath(nodes[102], nodes[304]));
		
	}

	void loop() {

		this.globalTime += timeIncrement;

		// panning and zooming with mouse and keyboard
		if (mousePressed) {

			mouseInitialPos = new Vector2(mousePosition).minus(Drawable.getMousePan());

		} if (mouseHeld) {

			Drawable.setMousePan(mousePosition.minus(mouseInitialPos));

		}

		if (keyHeld('A')) {

			Drawable.adjustPan(-Drawable.PAN_CONST, 0);

		} if (keyHeld('D')) {

			Drawable.adjustPan(Drawable.PAN_CONST, 0);

		} if (keyHeld('W')) {

			Drawable.adjustPan(0, Drawable.PAN_CONST);

		} if (keyHeld('S')) {

			Drawable.adjustPan(0, -Drawable.PAN_CONST);

		}

		if (keyHeld('=')) {

			Drawable.adjustZoom(Drawable.ZOOM_CONST);

		} if (keyHeld('-')) {

			Drawable.adjustZoom(-Drawable.ZOOM_CONST);

		}

		if (keyPressed('C')) {

			Drawable.resetPanZoom();

		}

		// draw game objects
		for (ComplexLine cl : complexLines) {

			Drawable.drawComplexLine(this, cl);

		}

		for (Node n : nodes) {

			Drawable.drawCircle(this, n);

		}

		for (Line l : lines) {

			for (Train t : l.getTrains()) {

				t.updatePosAlongLine();
				Drawable.drawCircle(this, t);
				Drawable.drawString(this, t, t.getLine().getID(), Vector3.black, Train.FONT_SIZE_CONST, Train.FONT_CENTERED);

			}

		}

	}

	public static Vector2 getMinMax(double[] arr) {

		if (arr == null || arr.length == 0) { return null; }

		double minInArr = arr[0];
		double maxInArr = arr[0];
		for (double i : arr) {

			if (i < minInArr) { minInArr = i; }
			if (i > maxInArr) { maxInArr = i; }

		}

		return new Vector2(minInArr, maxInArr);

	}

	public static boolean normalize(double[] arr, double min, double max, double minInArr, double maxInArr) {

		double maxMinDiff = max - min;
		double maxMinInArrDiff = maxInArr - minInArr;

		if (maxMinInArrDiff + min == 0) { return false; }

		for (int i = 0; i < arr.length; i++) {

			arr[i] = maxMinDiff * (arr[i] - minInArr) / (maxMinInArrDiff) + min;

		}

		return true;

	}

	public double getGlobalTime() { return this.globalTime; }
	public String toString() { return "City simulation running for " + globalTime + " ticks."; }

}

class Drawable {

	// considering adding methods to allow these to be changed
	final static double ZOOM_CONST = 0.05;
	final static double ZOOM_MAX = 4;
	final static double ZOOM_MIN = 0.1;
	final static double PAN_CONST = 0.25;
	final static double PAN_X_MINMAX = 50;
	final static double PAN_Y_MINMAX = 50;

	private static double zoom;
	private static Vector2 pan;
	private static Vector2 mousePan;

	private String id;
	private Vector2 pos;
	private Vector3 color;
	private double size;

	public Drawable(Vector2 pos, Vector3 color, double size) {

		this.id = "";
		this.pos = pos;
		this.color = color;
		this.size = size;

	}

	public Drawable(String id, Vector2 pos, Vector3 color, double size) {

		this.id = id;
		this.pos = pos;
		this.color = color;
		this.size = size;

	}

	public static void adjustPan(double x, double y) {

		pan = pan.minus(new Vector2(x, y));
		constrictPan(pan);

	}

	public static void constrictPan(Vector2 pan) {

		pan.x = Math.min(Math.max(pan.x, -PAN_X_MINMAX), PAN_X_MINMAX);
		pan.y = Math.min(Math.max(pan.y, -PAN_Y_MINMAX), PAN_Y_MINMAX);

	}

	public static void adjustZoom(double z) {

		zoom = Math.min(Math.max(zoom+z, ZOOM_MIN), ZOOM_MAX);

	}

	public static void drawCircle(App a, Drawable d) {

		drawCircle(a, d, d.getColor());

	}

	public static void drawCircle(App a, Drawable d, Vector3 col) {

		a.drawCircle(d.pos.plus(pan).plus(mousePan).times(zoom), d.size * zoom, col);

	}

	public static void drawLine(App a, Drawable d1, Drawable d2) {

		drawLine(a, d1, d2, d2.getColor());

	}

	public static void drawLine(App a, Drawable d1, Drawable d2, Vector3 col) {

		a.drawLine(d1.pos.plus(pan).plus(mousePan).times(zoom), d2.pos.plus(pan).plus(mousePan).times(zoom), col);

	}

	public static void drawComplexLine(App a, ComplexLine cl) {

		drawComplexLine(a, cl, cl.getColor());

	}

	public static void drawComplexLine(App a, ComplexLine cl, Vector3 col) {

		for (int i = 1; i < cl.getNodesSize(); i++) {

			drawLine(a, cl.getNode(i-1), cl.getNode(i), col);

		}

	}

	public static void drawString(App a, Drawable d, String str, int size, boolean centered) {

		drawString(a, d, str, d.getColor(), size, centered);

	}

	public static void drawString(App a, Drawable d, String str, Vector3 col, int size, boolean centered) {

		a.drawString(str, d.pos.plus(pan).plus(mousePan).times(zoom), col, (int) Math.ceil(size * zoom), centered);

	}

	public static void resetPanZoom() { pan = new Vector2(); mousePan = new Vector2(); zoom = 1; }
	public static void setPan(Vector2 p) { constrictPan(p); pan = p; }
	public static void setMousePan(Vector2 mp) { constrictPan(mp); mousePan = mp; }
	public static void setZoom(double z) { zoom = z; }
	public static Vector2 getPan() { return pan; }
	public static Vector2 getMousePan() { return mousePan; }
	public static double getZoom() { return zoom; }

	public void setID(String id) { this.id = id; }
	public void setPos(Vector2 pos) { this.pos = pos; }
	public void setPos(double x, double y) { this.pos.x = x; this.pos.y = y; }
	public void setX(double x) { this.pos.x = x; }
	public void setY(double y) { this.pos.y = y; }
	public void setColor(Vector3 col) { this.color = col; }
	public void setSize(double size) { this.size = size; }
	public String getID() { return this.id; }
	public Vector2 getPos() { return this.pos; }
	public double getX() { return this.pos.x; }
	public double getY() { return this.pos.y; }
	public Vector3 getColor() { return this.color; }
	public double getSize() { return this.size; }
	public String toString() { return "Drawable id=" + this.id + " pos=" + this.pos; }

}

// TODO
class Citizen extends Drawable {

	public Citizen(Vector2 pos, Vector3 color, double size) {

		super(pos, color, size);

	}

}

class Node extends Drawable {

	public static final double DEFAULT_NODE_SIZE = 0.5;
	public static final double DEFAULT_TRANSFER_WEIGHT = 10;
	public static final double DEFAULT_TRANSFER_MAX_DIST = 5;
	public static final double DEFAULT_CONST_STOP_PENALTY = Train.DEFAULT_STOP_DURATION;

	private HashMap<Node, Double> neighbors;

	private double ridership;
	private double f;
	
	private int xSegmentIndex;
	private int ySegmentIndex;

	public Node(Vector2 pos, Vector3 color) {

		super(pos, color, DEFAULT_NODE_SIZE);
		clearNeighbors();
		this.f = 0; this.ridership = 0;

	}

	public Node(Vector2 pos, Vector3 color, double size) {

		super(pos, color, size);
		clearNeighbors();
		this.f = 0; this.ridership = 0;
		
	}

	public Node(String id, Vector2 pos, Vector3 color) {

		super(id, pos, color, DEFAULT_NODE_SIZE);
		clearNeighbors();
		this.f = 0; this.ridership = 0;

	}

	public Node(String id, Vector2 pos, Vector3 color, double size) {

		super(id, pos, color, size);
		clearNeighbors();
		this.f = 0; this.ridership = 0;

	}

	public void addNeighbor(Node n, double d) {

		neighbors.put(n, d);

	}

	public void addNeighbors(Node[] n, double[] d) {

		assert n != null && d != null && n.length == d.length;

		for (int i = 0; i < n.length; i++) {

			neighbors.put(n[i], d[i]);

		}

	}

	public static void addNeighborPair(Node a, Node b, double dist) {

		a.addNeighbor(b, dist);
		b.addNeighbor(a, dist);

	}

	// pathfinding
	public double getScore() { return this.f; }
	public void setScore(double f) { this.f = f; }

	public static ArrayList<Node> findPath(Node start, Node end) {

		PriorityQueue<Node> queue = new PriorityQueue<>(Comparator.comparingDouble(Node::getScore));
		Set<Node> visited = new HashSet<>();
		HashMap<Node, Node> from = new HashMap<Node, Node>();
		HashMap<Node, Double> score = new HashMap<Node, Double>();

		score.put(start, 0.0);
		start.setScore(score.get(start) + scoreHeuristic(start, end));
		queue.add(start);

		while (!queue.isEmpty()) {

			Node current = queue.poll();

			if (current.equals(end)) {

				ArrayList<Node> path = reconstructPath(from, end);
				path.add(end);
				return path;

			}

			visited.add(current);

			for (Node neighbor : current.getNeighbors().keySet()) {

				if (visited.contains(neighbor)) { continue; }

				double tempScore = score.get(current) + current.getNeighbors().get(neighbor);

				if (!queue.contains(neighbor) || tempScore < score.get(neighbor)) {
					
					from.put(neighbor, current);
					score.put(neighbor, tempScore);
					neighbor.setScore(score.get(neighbor) + scoreHeuristic(neighbor, end));

					if (!queue.contains(neighbor)) {
						
						queue.add(neighbor);
					
					}
				
				}
			
			}
		}

		return null; // no path

	}
	
	private static double scoreHeuristic(Node a, Node b) {
		
		return Vector2.distanceBetween(a.getPos(), b.getPos()) + DEFAULT_CONST_STOP_PENALTY;
		
	}

	private static ArrayList<Node> reconstructPath(HashMap<Node, Node> from, Node current) {

		ArrayList<Node> path = new ArrayList<>();

		while (from.containsKey(current)) {

			current = from.get(current);
			path.add(current);

		}

		for (int i = 0; i < path.size()/2; i++) {

			int j = path.size()-1-i;
			Node temp = path.get(i);
			path.set(i, path.get(j));
			path.set(j, temp);

		}
		
		return path;

	}

	public void clearNeighbors() { neighbors = new HashMap<Node, Double>(); }
	public void setRidership(double d) { this.ridership = d; }
	public void setSegmentIndex(int x, int y) { this.xSegmentIndex = x; this.ySegmentIndex = y; }
	public HashMap<Node, Double> getNeighbors() { return this.neighbors; }
	public double getRidership() { return this.ridership; }
	public int getXSegmentIndex() { return this.xSegmentIndex; }
	public int getYSegmentIndex() { return this.ySegmentIndex; }
	public String toString() { return "Node id=" + this.getID() + " pos=" + this.getPos(); }

}

class ComplexLine extends Drawable {

	private Node[] nodes;
	private double length;

	public ComplexLine(String id, Vector3 col, Node[] nodes, double length) {

		super(id, nodes[0].getPos(), col, -1);
		this.nodes = nodes;
		this.length = length;

	}

	public void setNodes(Node[] nodes) { this.nodes = nodes; }
	public void setLength(double length) { this.length = length; }
	public Node[] getNodes() { return this.nodes; }
	public Node getNode(int i) { return this.nodes[i]; }
	public int getNodesSize() { return nodes.length; }
	public double getLength() { return length; }
	public String toString() { return "ComplexLine id=" + this.getID() + " nodes=" + Arrays.deepToString(nodes); }

}

class Train extends Drawable {

	public static final double DEFAULT_TRAIN_SIZE = 1.0;
	public static final double DEFAULT_STOP_DURATION = 6;
	public static final double DEFAUlT_TRAIN_SPEED = 1.0;
	public static final int FONT_SIZE_CONST = 11;
	public static final boolean FONT_CENTERED = true;

	private Line line;
	private int stop;
	private double globalTime;
	private double stopTime;
	private double stoppedTime;
	private double speed;

	public Train(int spawnStop, Line line, Vector3 color, double speed) { 

		super(line.getStop(spawnStop).getPos(), color, DEFAULT_TRAIN_SIZE);
		this.line = line;
		this.stop = spawnStop;
		this.speed = speed;

	}

	public void updatePosAlongLine() {

		int nextStop = (this.stop+1) % line.getLength();
		globalTime += speed;
		stopTime += speed;

		if (stopTime >= this.line.getDist(nextStop)) {

			// go to next station
			if (stoppedTime >= DEFAULT_STOP_DURATION) {

				stopTime = 0;
				stoppedTime = 0;
				stop = nextStop;
				setPos(this.line.getStop(stop).getPos());

				// wait at station
			} else {

				stoppedTime += speed;

			}

		} else {

			// move along line
			setPos(Vector2.lerp(stopTime/this.line.getDist(nextStop), this.line.getStop(stop).getPos(), this.line.getStop(nextStop).getPos()));

		}

	}

	public void setSpeed(double speed) { this.speed = speed; }
	public Line getLine() { return this.line; }
	public Node getStop() { return this.line.getStop(this.stop); }
	public int getStopIndex() { return this.stop; }
	public double getGlobalTime() { return this.globalTime; }
	public double getStopTime() { return this.stopTime; }
	public double getStoppedTime() { return this.stoppedTime; }
	public double getSpeed() { return this.speed; }
	public String toString() { return "Train id=" + this.getID() + " Line=" + this.getLine().getID() + " pos=" + this.getPos(); }

}

class Line {

	private String id;
	private Vector3 color;

	private Node[] stops;
	private double[] dists;
	private Train[] trains;

	public Line(String id) {

		this.id = id;

	}

	public Line(String id, Node[] stops, double[] dists, Train[] trains) {

		this(id);
		this.stops = stops;
		this.dists = dists;
		this.trains = trains;

	}

	public void addStop(Node stop, double dist) {

		if (this.stops == null) { this.stops = new Node[0]; }
		if (this.dists == null) { this.dists = new double[0]; }
		Node[] newStops = new Node[this.stops.length + 1];
		double[] newDists = new double[this.stops.length + 1];
		assert this.stops.length == this.dists.length;

		for (int i = 0; i < this.stops.length; i++) {

			newStops[i] = this.stops[i];
			newDists[i] = this.dists[i];

		}

		newStops[newStops.length-1] = stop;
		newDists[newDists.length-1] = dist;

		this.stops = newStops;
		this.dists = newDists;

	}

	// reconfigure stop list according to preset stop pattern
	public void rearrangeStops(String[] stopConfig) {

		ArrayList<Node> newStops = new ArrayList<Node>();
		for (String s : stopConfig) {

			for (Node n : this.stops) {

				if (n.getID().equals(s.replaceAll("_", " "))) {

					newStops.add(n);

				}

			}

		}

		int size = newStops.size();
		for (int i = 1; i < size; i++) {

			newStops.add(newStops.get(size-1-i));

		}

		double[] newDists = new double[newStops.size()];
		for (int i = 0; i < newStops.size()-1; i++) {

			double dist = Vector2.distanceBetween(newStops.get(i).getPos(), newStops.get(i+1).getPos());
			newDists[i+1] = dist;
			Node.addNeighborPair(newStops.get(i), newStops.get(i+1), dist);

		}

		Node[] newStopsArray = new Node[newStops.size()];
		for (int i = 0; i < newStopsArray.length; i++) {

			newStopsArray[i] = newStops.get(i);

		}

		this.stops = newStopsArray;
		this.dists = newDists;

	}

	// set stop colors to line color
	public void overrideStopColors() {

		for (int i = 0; i < stops.length; i++) {

			stops[i].setColor(color);

		}

	}

	public void addTrain(Train train) {

		if (this.trains == null) { this.trains = new Train[0]; }
		Train[] trainsNew = new Train[this.trains.length + 1];

		for (int i = 0; i < this.trains.length; i++) {

			trainsNew[i] = this.trains[i];

		}

		trainsNew[trainsNew.length-1] = train;
		this.trains = trainsNew;

	}

	public void setStops(Node[] stops, double[] dists) { this.stops = stops; this.dists = dists; }
	public void setTrains(Train[] trains) { this.trains = trains; }
	public void setColor(Vector3 col) { this.color = col; }
	public void setID(String id) { this.id = id; }
	public Node[] getStops() { return this.stops; }
	public double[] getDists() { return this.dists; }
	public Node getStop(int i) { return this.stops[i]; }
	public double getDist(int i) { return this.dists[i]; }
	public int getLength() { return this.stops.length; }
	public Train[] getTrains() { return this.trains; }
	public Vector3 getColor() { return this.color; }
	public String getID() { return this.id; }
	public String toString() { return "Line id= " + this.id + " stops=" + Arrays.toString(this.stops); }

}

class Vector3Mod extends Vector3 {

	Vector3Mod(String hex) {

		this.x = (double)Integer.parseInt(hex.substring(0, 2), 16)/255.0;
		this.y = (double)Integer.parseInt(hex.substring(2, 4), 16)/255.0;
		this.z = (double)Integer.parseInt(hex.substring(4, 6), 16)/255.0;

	}

}