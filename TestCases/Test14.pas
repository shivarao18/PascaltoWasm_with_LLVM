program TestDestructor;

type
  MyClass = class;
  public
    X: Integer;
    constructor Create;
    destructor Destroy;
  end;

constructor MyClass.Create;
begin
  X := 99;
end;

destructor MyClass.Destroy;
begin
  WRITELN('Destructor called');
end;

var
  Obj: MyClass;

begin
  Obj := MyClass.Create;
  WRITELN(Obj.X);
  Obj.Destroy;
end.