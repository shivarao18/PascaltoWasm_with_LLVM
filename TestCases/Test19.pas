program TestEverything;

type
  MyClass = class;
  public
    Count: Integer;
    constructor Create;
    destructor Destroy;
    procedure LoopWithWhile;
  end;

constructor MyClass.Create;
begin
  Count := 0;
end;

destructor MyClass.Destroy;
begin
  WRITELN('Goodbye from destructor');
end;

procedure MyClass.LoopWithWhile;
var
  I: Integer;
begin
  I := 0;
  while I < 5 do
  begin
    I := I + 1;
    if I = 2 then
    begin
        continue;
    end;
    if I = 4 then
    begin
        break;
    end;
    WRITELN('While:', I);
  end;
end;

procedure SayHello;
begin
  WRITELN('Hello from a procedure');
end;

function SumTo(N: Integer): Integer;
var
  I, Sum: Integer;
begin
  Sum := 0;
  for I := 1 to N do
  begin
    Sum := Sum + I;
  end;
  SumTo := Sum;
end;

var
  Obj: MyClass;
  Result: Integer;

begin
  SayHello;
  Obj := MyClass.Create;
  Obj.LoopWithWhile;
  Result := SumTo(5);
  WRITELN('Sum:', Result);
  Obj.Destroy;
end.